-if class com.aura.music.data.network.SyncOperationDto
-keepnames class com.aura.music.data.network.SyncOperationDto
-if class com.aura.music.data.network.SyncOperationDto
-keep class com.aura.music.data.network.SyncOperationDtoJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.aura.music.data.network.SyncOperationDto
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.aura.music.data.network.SyncOperationDto
-keepclassmembers class com.aura.music.data.network.SyncOperationDto {
    public synthetic <init>(java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.util.Map,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
