-if class com.aura.music.data.network.ArtistDetailResponseData
-keepnames class com.aura.music.data.network.ArtistDetailResponseData
-if class com.aura.music.data.network.ArtistDetailResponseData
-keep class com.aura.music.data.network.ArtistDetailResponseDataJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.aura.music.data.network.ArtistDetailResponseData
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.aura.music.data.network.ArtistDetailResponseData
-keepclassmembers class com.aura.music.data.network.ArtistDetailResponseData {
    public synthetic <init>(java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.util.List,java.util.List,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
