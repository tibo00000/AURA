-if class com.aura.music.data.network.AlbumSummary
-keepnames class com.aura.music.data.network.AlbumSummary
-if class com.aura.music.data.network.AlbumSummary
-keep class com.aura.music.data.network.AlbumSummaryJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.aura.music.data.network.AlbumSummary
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.aura.music.data.network.AlbumSummary
-keepclassmembers class com.aura.music.data.network.AlbumSummary {
    public synthetic <init>(java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.Integer,java.lang.String,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
