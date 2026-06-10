-if class com.aura.music.data.network.ServerChangeDto
-keepnames class com.aura.music.data.network.ServerChangeDto
-if class com.aura.music.data.network.ServerChangeDto
-keep class com.aura.music.data.network.ServerChangeDtoJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
