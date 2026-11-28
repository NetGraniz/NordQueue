# Building NordQueue

Requirements:

- JDK 25
- Maven 3.9+

Run:

```powershell
.\build.ps1 -MavenCommand "C:\path\to\mvn.cmd"
```

The script runs `mvn clean verify`, checks the exact shaded release JAR, and validates `velocity-plugin.json`. The production artifact is `target/NordQueue-2.0.2.jar`.
