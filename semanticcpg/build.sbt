name := "semanticcpg"

libraryDependencies ++= Seq(
  "io.shiftleft"           %% "codepropertygraph" % Versions.cpg,
  "com.michaelpollmeier"   %% "scala-repl-pp"     % Versions.scalaReplPP,
  "org.json4s"             %% "json4s-native"     % Versions.json4s,
  "org.scala-lang.modules" %% "scala-xml"         % "2.2.0",
  "org.apache.commons"      % "commons-text"      % Versions.commonsText,
  "org.scalatest"          %% "scalatest"         % Versions.scalatest % Test
)

Compile / doc / scalacOptions ++= Seq("-doc-title", "semanticcpg apidocs", "-doc-version", version.value)

githubOwner      := "Privado-Inc"
githubRepository := "joern"
credentials +=
  Credentials(
    "GitHub Package Registry",
    "maven.pkg.github.com",
    "Privado-Inc",
    sys.env.getOrElse("GITHUB_TOKEN", "N/A")
  )
