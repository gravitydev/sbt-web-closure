sbt-web-gss
===========

Allows closure-stylesheets to be used from within sbt. No requirements on nodejs, or anything like that. All JVM.
To use this plugin use the addSbtPlugin command within your project's plugins.sbt (or as a global setting) i.e.:

```scala
resolvers += "gravity" at "https://devstack.io/repo/gravitydev/public"

addSbtPlugin("com.gravitydev" % "sbt-web-gss" % "0.0.3-SNAPSHOT")
```

Your project's build file also needs to enable sbt-web plugins. For example with build.sbt:

    lazy val root = (project in file(".")).enablePlugins(SbtGss)

Here are the options:

Option              | Description
--------------------|------------
gss                 | Invoke the closure-stylesheets compiler and compile gss files.
gssPrettyPrint      | Generate pretty CSS output.
gssAllowUnrecognizedFunctions | 
gssAllowUnrecognizedProperties |
gssFunctionMapProvider |
gssAllowedNonStandardFunctions | Allowed non standard functions.
    
The following sbt code illustrates how you use a setting:

```scala
GssKeys.gssAllowedNonStandardFunctions := List(
  "color-stop",
  "progid:DXImageTransform.Microsoft.gradient",
  "progid:DXImageTransform.Microsoft.Shadow"
)
```

By default all `*.gss` files are compiled. You can also specify a different filter in your `build.sbt` like the
following:

```scala
includeFilter in (Assets, GssKeys.less) := "foo.gss" | "bar.gss"
```

...where both `foo.gss` and `bar.gss` will be considered for the GSS compiler.

Alternatively you may want a more general expression to exclude GSS files that are not considered targets
for the compiler. Quite commonly, GSS files are divided up into those entry point files and other files, with the
latter set intended for importing into the entry point files. These other files tend not to be suitable for the
compiler in isolation as they can depend on the global declarations made by other non-imported LESS files. The
pugin excludes any GSS file starting with an `_` from direct compilation. To modify you can redefine the excludeFilter:

```scala
// this is the default, replace it with something else if you want
excludeFilter in (Assets, GssKeys.less) := "_*.gss"
```
