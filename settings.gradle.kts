pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "LINKNAV"
include(":app")
include(":core-ui")
include(":core-map")
include(":core-location")
include(":core-routing")
include(":core-navigation")
include(":core-search")
include(":core-network")
include(":core-ai")
include(":core-offline")
include(":core-camera")
include(":core-ar")
include(":core-vision")
include(":core-voice")
include(":core-webrtc")
include(":core-messaging")
include(":core-security")
include(":feature-map")
include(":feature-search")
include(":feature-navigation")
include(":feature-ar-navigation")
include(":feature-duo")
include(":feature-chat")
include(":feature-call")
include(":feature-video-call")
include(":feature-profile")
include(":feature-history")
include(":feature-saved")
include(":feature-settings")
