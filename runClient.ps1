$ErrorActionPreference = "Stop"

$candelaRoot = $PSScriptRoot

Push-Location $candelaRoot
try {
	.\gradlew.bat --stop
	$env:JAVA_TOOL_OPTIONS='-Xmx8G -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC'
	.\gradlew.bat runClient --args="--renderDebugLabels --graphicsBackend VULKAN"
} finally {
	Pop-Location
}
