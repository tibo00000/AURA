-if class com.aura.music.data.network.TrackSummary
-keepnames class com.aura.music.data.network.TrackSummary
-if class com.aura.music.data.network.TrackSummary
-keep class com.aura.music.data.network.TrackSummaryJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.aura.music.data.network.TrackSummary
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.aura.music.data.network.TrackSummary
-keepclassmembers class com.aura.music.data.network.TrackSummary {
    public synthetic <init>(java.lang.String,java.lang.String,java.lang.String,java.lang.String,int,java.lang.String,boolean,boolean,boolean,boolean,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
