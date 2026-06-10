-if class com.aura.music.data.network.JobErrorPayload
-keepnames class com.aura.music.data.network.JobErrorPayload
-if class com.aura.music.data.network.JobErrorPayload
-keep class com.aura.music.data.network.JobErrorPayloadJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
