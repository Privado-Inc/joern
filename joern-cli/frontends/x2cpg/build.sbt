name := "x2cpg"

dependsOn(Projects.semanticcpg)

libraryDependencies ++= Seq(
  /* Start: AST Gen Dependencies */
  "com.lihaoyi"         %% "upickle"      % Versions.upickle,
  "com.typesafe"         % "config"       % Versions.typeSafeConfig,
  "com.michaelpollmeier" % "versionsort"  % Versions.versionSort,
  "org.apache.commons"   % "commons-exec" % Versions.commonsExec,
  /* End: AST Gen Dependencies */
  "net.freeutils"  % "jlhttp"             % Versions.jlhttp,
  "org.gradle"     % "gradle-tooling-api" % Versions.gradleTooling % Optional,
  "org.scalatest" %% "scalatest"          % Versions.scalatest     % Test
)

Compile / doc / scalacOptions ++= Seq("-doc-title", "semanticcpg apidocs", "-doc-version", version.value)

compile / javacOptions ++= Seq("-Xlint:all", "-Xlint:-cast", "-g")
Test / fork := true

enablePlugins(JavaAppPackaging)

Universal / packageName       := name.value
Universal / topLevelDirectory := None

githubOwner      := "Privado-Inc"
githubRepository := "joern"

credentials +=
  Credentials(
    "GitHub Package Registry",
    "maven.pkg.github.com",
    "Privado-Inc",
    sys.env.getOrElse("GITHUB_TOKEN", "N/A")
  )
