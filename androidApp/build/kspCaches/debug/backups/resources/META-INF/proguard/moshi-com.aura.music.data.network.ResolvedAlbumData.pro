-if class com.aura.music.data.network.ResolvedAlbumData
-keepnames class com.aura.music.data.network.ResolvedAlbumData
-if class com.aura.music.data.network.ResolvedAlbumData
-keep class com.aura.music.data.network.ResolvedAlbumDataJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.aura.music.data.network.ResolvedAlbumData
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.aura.music.data.network.ResolvedAlbumData
-keepclassmembers class com.aura.music.data.network.ResolvedAlbumData {
    public synthetic <init>(java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.Integer,java.lang.String,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
