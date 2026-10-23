rootProject.name = "demo-web-app"

// Demo w AInvoicer używa oficjalnego SDK ze wskazanego lokalnego checkoutu.
// Można zmienić lokalizację przez -PksefSdkDir=... albo KSEF_SDK_DIR.
val sdkDir = providers.gradleProperty("ksefSdkDir")
    .orElse(providers.environmentVariable("KSEF_SDK_DIR"))
    .getOrElse("../../ksef-java-client/ksef-client-java/ksef-client")
include(":ksef-client")
project(":ksef-client").projectDir = file(sdkDir)
require(file("$sdkDir/build.gradle.kts").isFile) {
    "Nie znaleziono oficjalnego SDK. Ustaw KSEF_SDK_DIR lub -PksefSdkDir (katalog ksef-client)."
}
