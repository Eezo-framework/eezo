package io.eezo.http

import io.eezo.http.PathPattern.Segment

/** A path pattern, in three segment kinds: a literal, a `:name` that captures one segment, and a
  * `*name` that captures everything left.
  *
  * There are no regex constraints, no optional segments and no typed patterns. A typed pattern
  * makes `Route` generic over its parameter tuple, which collides with the flat route table, and a
  * regex constraint on a segment is the wrong home for what `FromPath[A]` already does by failing
  * to a 400.
  */
final class PathPattern private (val segments: Vector[Segment]) {

  /** The captured parameters when `path` matches, and nothing when it does not. */
  def matchPath(path: String): Option[Map[String, String]] = {
    val parts = PathPattern.split(path)

    def loop(
        pattern: List[Segment],
        remaining: List[String],
        captured: Map[String, String]
    ): Option[Map[String, String]] =
      (pattern, remaining) match {
        case (Nil, Nil)                             => Some(captured)
        case (Segment.CatchAll(name) :: Nil, rest)  => Some(captured + (name -> rest.mkString("/")))
        case (Segment.Static(value) :: ps, p :: rs) =>
          if (value == p) loop(ps, rs, captured) else None
        case (Segment.Param(name) :: ps, p :: rs) => loop(ps, rs, captured + (name -> p))
        case _                                    => None
      }

    loop(segments.toList, parts.toList, Map.empty)
  }

  /** Whether every path this pattern matches, a later pattern would have matched too.
    *
    * The subsumption an earlier route needs to shadow a later one, decidable segment by segment
    * because there are only three segment kinds: a `Static` swallows only an equal `Static`, a
    * `Param` swallows any single segment, and a `CatchAll` swallows everything remaining. It is a
    * one-way question, so `/widgets/:id` subsumes `/widgets/new` and never the reverse.
    *
    * The two ragged ends are asymmetric for the same reason `matchPath` is: a trailing `CatchAll`
    * matches zero segments, so a pattern ending in one subsumes the prefix it is appended to, while
    * a pattern that runs out of segments first subsumes nothing.
    */
  def subsumes(later: PathPattern): Boolean = {
    def loop(mine: List[Segment], theirs: List[Segment]): Boolean = (mine, theirs) match {
      case (Nil, Nil) => true
      // `_` rather than `Nil`, because a non-final catch-all is unrepresentable after `parse`
      // and matching on `Nil` only buys an inexhaustivity warning for the state that cannot exist.
      case (Segment.CatchAll(_) :: _, _)                              => true
      case (Nil, _) | (_, Nil)                                        => false
      case (Segment.Static(value) :: ms, Segment.Static(other) :: ts) =>
        value == other && loop(ms, ts)
      case (Segment.Static(_) :: _, _)                       => false
      case (Segment.Param(_) :: _, Segment.CatchAll(_) :: _) => false
      case (Segment.Param(_) :: ms, _ :: ts)                 => loop(ms, ts)
    }

    loop(segments.toList, later.segments.toList)
  }

  /** The way back to the string this was parsed from. The `dev = true` boot print and the boot time
    * warnings both name a path, and neither can reach for the source text.
    */
  def render: String =
    if (segments.isEmpty) "/"
    else
      segments
        .map {
          case Segment.Static(value)  => value
          case Segment.Param(name)    => s":$name"
          case Segment.CatchAll(name) => s"*$name"
        }
        .mkString("/", "/", "")

  override def equals(other: Any): Boolean = other match {
    case that: PathPattern => segments == that.segments
    case _                 => false
  }

  override def hashCode(): Int = segments.hashCode()

  override def toString: String = render
}

object PathPattern {

  enum Segment {
    case Static(value: String)
    case Param(name: String)
    case CatchAll(name: String)
  }

  /** Parses a pattern, and fails the boot when it cannot.
    *
    * Two validations, both fatal, because `parse` runs at boot and both are bugs a developer wants
    * named rather than routed around: a repeated parameter name, whose second capture would
    * silently win, and a catch-all before the final segment, which can never match what its author
    * meant.
    */
  def parse(pattern: String): PathPattern = {
    val raws     = split(pattern)
    val segments = raws.zipWithIndex.map { case (raw, index) =>
      if (raw.startsWith("*")) {
        require(
          index == raws.size - 1,
          s"catch-all segment '$raw' in pattern '$pattern' must be the final segment"
        )
        Segment.CatchAll(raw.drop(1))
      } else if (raw.startsWith(":")) Segment.Param(raw.drop(1))
      else Segment.Static(raw)
    }

    val names = segments.collect {
      case Segment.Param(name)    => name
      case Segment.CatchAll(name) => name
    }
    names.diff(names.distinct).headOption.foreach { duplicate =>
      throw new IllegalArgumentException(
        s"parameter name '$duplicate' appears twice in pattern '$pattern'"
      )
    }

    new PathPattern(segments)
  }

  /** Splits a path or a pattern into segments, normalising a trailing slash away so that `/widgets`
    * and `/widgets/` are one path.
    */
  private def split(path: String): Vector[String] =
    path.split('/').iterator.filter(_.nonEmpty).toVector
}
