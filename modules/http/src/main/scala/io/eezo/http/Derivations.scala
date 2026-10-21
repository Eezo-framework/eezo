package io.eezo.http

import scala.compiletime.summonFrom

import io.eezo.core.Store

/** The derivations that mount routes, asked about as one: what a model mounts, and whether it
  * mounts anything at all.
  *
  * The sbt plugin writes one call into this object per candidate case class and nothing else about
  * derivations, because the generator reads text and only the compiler can say which typeclasses a
  * model has: it sees through a type alias and finds a hand written instance, and a model with none
  * compiles to nothing rather than failing. Asking here rather than in the generated file is what
  * keeps that file from listing derivations at all, so the line it writes never changes when eezo
  * gains one, and the list lives beside the derivations it names instead of in a template string
  * that has to be kept in step with them.
  */
object Derivations {

  /** A model's routes: the seven of its [[Resource]] and then the five of its [[Api]], each nothing
    * when the model has no instance.
    *
    * The seven come first so that a model mounting both reads down a listing its pages and then its
    * program's routes, and no JSON route can shadow a page, since every path the five answer on is
    * under `/api`. Each half is handed to the derivation's own `routesOf`, so what the declaration
    * carries goes in front of the pages and never in front of the API, by the rule each of them
    * already keeps.
    *
    * The parameters are by value on purpose. The generated row passes `storeFor[A]`, which mints a
    * store where it is written, so an inline parameter would mint one for the pages and another for
    * the program, and a row a program created would never show on a page. By value, the call
    * evaluates each argument once and both halves read the same one.
    */
  inline def routesOf[A](store: Store[A], guarded: Guarded[A]): Seq[Route] =
    Resource.routesOf[A](store, guarded) ++ Api.routesOf[A](store, guarded)

  /** Whether a model has any derivation that mounts a route.
    *
    * Asked by the generated table under the strict reading, where a route that says nothing about
    * who may reach it is a compile error: a case class with a `derives` clause that mounts nothing,
    * such as a model that derives a `Table` alone, has no route to say it about. A `Boolean` that
    * folds to a constant at the call site, so an `inline if` on it never expands the branch it does
    * not take, and the error in that branch is raised only for a model that mounts something.
    */
  inline def mounts[A]: Boolean = summonFrom {
    case _: Resource[A] => true
    case _: Api[A]      => true
    case _              => false
  }
}
