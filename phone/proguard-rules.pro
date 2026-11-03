# CXR-L SDK (from Rokid Quick Start)
-keep class com.rokid.cxr.** { *; }

# kotlinx.serialization protocol classes
-keep class com.rokidos885.protocol.** { *; }

# HUD auto-update reaches the session SDK client by field type (CxrGlassesLink.linkClientOf).
-keep class com.rokid.sprite.aiapp.externalapp.** { *; }
