-if class com.aura.music.data.network.DownloadRequestDto
-keepnames class com.aura.music.data.network.DownloadRequestDto
-if class com.aura.music.data.network.DownloadRequestDto
-keep class com.aura.music.data.network.DownloadRequestDtoJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.aura.music.data.network.DownloadRequestDto
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.aura.music.data.network.DownloadRequestDto
-keepclassmembers class com.aura.music.data.network.DownloadRequestDto {
    public synthetic <init>(java.lang.String,com.aura.music.data.network.SourceHintDto,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
