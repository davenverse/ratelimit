---
layout: home

---

# ratelimit - Rate Limiting [![Build Status](https://travis-ci.com/ChristopherDavenport/ratelimit.svg?branch=master)](https://travis-ci.com/ChristopherDavenport/ratelimit) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/ratelimit_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/ratelimit_2.12)

## Quick Start

To use ratelimit in an existing SBT project with Scala 2.11 or a later version, add the following dependencies to your
`build.sbt` depending on your needs:

```scala
libraryDependencies ++= Seq(
  "io.chrisdavenport" %% "ratelimit" % "<version>"
)
```