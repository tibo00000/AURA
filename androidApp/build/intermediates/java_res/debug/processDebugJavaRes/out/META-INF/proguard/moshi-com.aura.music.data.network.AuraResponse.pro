-if class com.aura.music.data.network.AuraResponse
-keepnames class com.aura.music.data.network.AuraResponse
-if class com.aura.music.data.network.AuraResponse
-keep class com.aura.music.data.network.AuraResponseJsonAdapter {
    public <init>(com.squareup.moshi.Moshi,java.lang.reflect.Type[]);
}
