-if class com.aura.music.data.network.ArtistSummary
-keepnames class com.aura.music.data.network.ArtistSummary
-if class com.aura.music.data.network.ArtistSummary
-keep class com.aura.music.data.network.ArtistSummaryJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.aura.music.data.network.ArtistSummary
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.aura.music.data.network.ArtistSummary
-keepclassmembers class com.aura.music.data.network.ArtistSummary {
    public synthetic <init>(java.lang.String,java.lang.String,java.lang.String,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
