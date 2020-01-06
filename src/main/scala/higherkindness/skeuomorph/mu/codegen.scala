/*
 * Copyright 2018-2020 47 Degrees, LLC. <http://www.47deg.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package higherkindness.skeuomorph.mu

import scala.meta._
import scala.meta.classifiers.Classifier
import scala.meta.Term.Block
import higherkindness.droste._
import higherkindness.skeuomorph.mu.MuF._
import higherkindness.skeuomorph.mu.Optimize._
import scala.reflect.ClassTag
import cats.syntax.either._
import cats.syntax.traverse._
import cats.instances.either._
import cats.instances.list._
import cats.syntax.apply._

object codegen {

  private implicit class TreeSyntax(val tree: Tree) extends AnyVal {
    def as[A](implicit tag: ClassTag[A], classifier: Classifier[Tree, A]): Either[String, A] =
      Either.cond(
        tree.is[A],
        tree.asInstanceOf[A],
        s"Expected a tree of type ${tag.runtimeClass.getName} but got: $tree (${tree.structure})"
      )
  }

  def protocol[T](
      protocol: Protocol[T],
      streamCtor: (Type, Type) => Type.Apply
  )(implicit T: Basis[MuF, T]): Either[String, Pkg] = {

    val packageName = protocol.pkg
      .getOrElse("proto")
      .parse[Term]
      .toEither
      .leftMap(e => s"Failed to parse package name: $e")
      .flatMap(_.as[Term.Ref])

    def declaration(decl: T): Either[String, List[Stat]] =
      for {
        tree <- schema(optimize(decl))
        stat <- tree.as[Stat]
      } yield explodeBlock(stat)

    val declarations: Either[String, List[Stat]] =
      protocol.declarations.flatTraverse(declaration)

    val services: Either[String, List[Stat]] =
      protocol.services.traverse(s => service(s, streamCtor))

    for {
      pkgName <- packageName
      decls   <- declarations
      srvs    <- services
    } yield {
      val objDefn = q"""
      // TODO options (Where should these annotations be attached to? And what are they for?)
      // TODO dependency imports
      object ${Term.Name(protocol.name)} {
        ..$decls
        ..$srvs
      }
      """
      Pkg(pkgName, List(objDefn))
    }
  }

  private def optimize[T](t: T)(implicit T: Basis[MuF, T]): T =
    // Apply optimizations to normalise the protocol
    // before converting it to Scala code
    (nestedOptionInCoproduct[T] andThen knownCoproductTypes[T]).apply(t)

  // A class and its companion object will be wrapped inside a Block (i.e. curly braces).
  // We need to extract them from there and lift them to the same level as other statements.
  private def explodeBlock(stat: Stat): List[Stat] = stat match {
    case Block(stats) => stats
    case other        => List(other)
  }

  def schema[T](decl: T)(implicit T: Basis[MuF, T]): Either[String, Tree] = {

    def identifier(prefix: List[String], name: String): Either[String, Type] = {
      if (prefix.isEmpty)
        Type.Name(name).asRight
      else {
        val path     = prefix.map(Term.Name(_))
        val pathTerm = path.foldLeft[Term](Term.Name("_root_")) { case (acc, name) => Term.Select(acc, name) }
        pathTerm.as[Term.Ref].map(p => Type.Select(p, Type.Name(name)))
      }
    }

    val algebra: AlgebraM[Either[String, ?], MuF, Tree] = AlgebraM {
      case TNull()                  => t"Null".asRight
      case TDouble()                => t"_root_.scala.Double".asRight
      case TFloat()                 => t"_root_.scala.Float".asRight
      case TInt()                   => t"_root_.scala.Int".asRight
      case TLong()                  => t"_root_.scala.Long".asRight
      case TBoolean()               => t"_root_.scala.Boolean".asRight
      case TString()                => t"_root_.java.lang.String".asRight
      case TByteArray()             => t"_root_.scala.Array[Byte]".asRight
      case TNamedType(prefix, name) => identifier(prefix, name)
      case TOption(value)           => value.as[Type].map(tpe => t"_root_.scala.Option[$tpe]")
      case TEither(a, b) =>
        (a.as[Type], b.as[Type]).mapN { case (aType, bType) => t"_root_.scala.Either[$aType, $bType]" }
      case TMap(Some(key), value) =>
        (key.as[Type], value.as[Type]).mapN { case (kType, vType) => t"_root_.scala.Predef.Map[$kType, $vType]" }
      case TMap(None, value) =>
        value
          .as[Type]
          .map(vType => t"_root_.scala.Predef.Map[_root_.java.lang.String, $vType]") // Compatibility for Avro
      case TGeneric(generic, tparams) =>
        for {
          tpe <- generic.as[Type]
          ts  <- tparams.traverse(_.as[Type])
        } yield t"$tpe[..$ts]"
      case TList(value)        => value.as[Type].map(tpe => t"_root_.scala.List[$tpe]")
      case TContaining(values) => values.traverse(_.as[Stat]).map(ss => q"..$ss")
      case TRequired(value)    => value.asRight
      case TCoproduct(invariants) =>
        invariants.toList.foldRight[Either[String, Type]](t"_root_.shapeless.CNil".asRight) {
          case (t: Tree, acc: Either[String, Type]) =>
            for {
              tType   <- t.as[Type]
              accType <- acc
            } yield t"_root_.shapeless.:+:[$tType, $accType]"
        }
      case TSum(name, fields) =>
        val typeName   = Type.Name(name)
        val fieldDefns = fields.map(f => q"case object ${Term.Name(f.name)} extends $typeName(${f.value})")
        q"""
        sealed abstract class $typeName(val value: _root_.scala.Int) extends _root_.enumeratum.values.IntEnumEntry
        object ${Term.Name(name)} extends _root_.enumeratum.values.IntEnum[$typeName] {
          ..$fieldDefns

          ;
          val values = findValues
        }
        """.asRight
      case TProduct(name, fields) =>
        def arg(f: Field[Tree]): Either[String, Term.Param] =
          f.tpe.as[Type].map { tpe =>
            val annotation = mod"@_root_.pbdirect.pbIndex(..${f.indices.map(Lit.Int(_))})"
            param"$annotation ${Term.Name(f.name)}: ${Some(tpe)}"
          }
        fields.traverse(arg).map { args =>
          q"@message final case class ${Type.Name(name)}(..$args)"
        }
    }

    scheme.cataM(algebra).apply(decl)
  }

  def service[T](srv: Service[T], streamCtor: (Type, Type) => Type.Apply)(
      implicit T: Basis[MuF, T]): Either[String, Stat] = {
    val serializationType = Term.Name(srv.serializationType.toString)
    val compressionType   = Term.Name(srv.compressionType.toString)

    val serviceAnnotation = srv.idiomaticEndpoints match {
      case IdiomaticEndpoints(Some(pkg), true) =>
        mod"@service($serializationType, $compressionType, namespace = Some($pkg), methodNameStyle = Capitalize)"
      case IdiomaticEndpoints(None, true) =>
        mod"@service($serializationType, $compressionType, methodNameStyle = Capitalize)"
      case _ =>
        mod"@service($serializationType, $compressionType)"
    }

    srv.operations.traverse(op => operation(op, streamCtor)).map { ops =>
      q"""
      @$serviceAnnotation trait ${Type.Name(srv.name)}[F[_]] {
        ..$ops
      }
      """
    }
  }

  def operation[T](op: Service.Operation[T], streamCtor: (Type, Type) => Type.Apply)(
      implicit T: Basis[MuF, T]): Either[String, Decl.Def] =
    for {
      reqType  <- requestType(op.request, streamCtor)
      respType <- responseType(op.response, streamCtor)
    } yield q"def ${Term.Name(op.name)}(req: $reqType): $respType"

  def requestType[T](opType: Service.OperationType[T], streamCtor: (Type, Type) => Type.Apply)(
      implicit T: Basis[MuF, T]): Either[String, Type] =
    for {
      tree <- schema(opType.tpe)
      tpe  <- tree.as[Type]
    } yield {
      if (opType.stream)
        streamCtor(Type.Name("F"), tpe)
      else
        tpe
    }

  def responseType[T](opType: Service.OperationType[T], streamCtor: (Type, Type) => Type.Apply)(
      implicit T: Basis[MuF, T]): Either[String, Type.Apply] =
    for {
      tree <- schema(opType.tpe)
      tpe  <- tree.as[Type]
    } yield {
      if (opType.stream)
        streamCtor(Type.Name("F"), tpe)
      else
        t"F[$tpe]"
    }

}
