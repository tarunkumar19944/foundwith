# kotlinx.serialization, OkHttp and Firebase ship their own R8 rules.
# Keep the API models' serializers (belt and braces for release builds).
-keepclassmembers @kotlinx.serialization.Serializable class app.foundwith.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class app.foundwith.push.MessagingService
