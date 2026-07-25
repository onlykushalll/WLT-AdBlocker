# ProGuard / R8 rules for WLT-Adblocker release builds.
#
# Currently minifyEnabled=false so this file is unused, but it's referenced
# from app/build.gradle.kts's release block — leaving it empty so the build
# works whether or not minify is later enabled.

# Keep gomobile binding entry points (if/when wlt.aar is added).
-keep class com.wlt.mobile.** { *; }

# Keep GoSecurityBridgeImpl so reflection can find it.
-keep class com.wlt.adblocker.vpn.GoSecurityBridgeImpl { *; }

# Kotlinx coroutines internals — R8 sometimes strips these incorrectly.
-keepclassmembers class kotlinx.coroutines.** { *; }
