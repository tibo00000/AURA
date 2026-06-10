-if class com.aura.music.data.network.ResolvedArtistData
-keepnames class com.aura.music.data.network.ResolvedArtistData
-if class com.aura.music.data.network.ResolvedArtistData
-keep class com.aura.music.data.network.ResolvedArtistDataJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.aura.music.data.network.ResolvedArtistData
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.aura.music.data.network.ResolvedArtistData
-keepclassmembers class com.aura.music.data.network.ResolvedArtistData {
    public synthetic <init>(java.lang.String,java.lang.String,java.lang.String,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
