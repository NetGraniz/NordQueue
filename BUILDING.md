# Building NordQueue

The supported release build uses Maven 3.9+ and JDK 25 on Windows, Linux or macOS.
Set JAVA_HOME to your own JDK 25 installation and put Maven on PATH. No live server
folder, private configuration, prebuilt old plugin, or machine-specific path is required.

## Build and tests

From this project's root, run `mvn clean verify`, or on PowerShell run
`./build.ps1`. The wrapper accepts `-MavenCommand /path/to/mvn`.
The JAR is `target/NordQueue-1.1.3.jar`.
Existing main-based regression checks are run by the JUnit adapter with assertions enabled.


Only plugin metadata resources are filtered for the release version. Configuration
templates are copied unchanged. API libraries are provided by Velocity and
are not bundled. Builds pin Paper API 26.2 build 129 or the timestamped Velocity
4.2.1 API snapshot rather than depending on a live server's library directory.
This release requires JDK 25 and targets Paper 26.2 or Velocity 4.2.1.

The older README and test-support fixtures may describe historical local
integration environments. BUILDING.md and pom.xml define the release build;
test-support is not packaged in the plugin JAR.

## Server settings

Install the JAR on a stopped server, start it to create its default files, then
configure your own server values. Do not publish installed config files or player
stores. Existing configuration must be backed up and reviewed before updating.
No deployment or server configuration change is performed by the build.
