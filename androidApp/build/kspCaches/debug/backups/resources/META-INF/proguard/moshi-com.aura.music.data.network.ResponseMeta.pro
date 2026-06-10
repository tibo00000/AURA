-if class com.aura.music.data.network.ResponseMeta
-keepnames class com.aura.music.data.network.ResponseMeta
-if class com.aura.music.data.network.ResponseMeta
-keep class com.aura.music.data.network.ResponseMetaJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.aura.music.data.network.ResponseMeta
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.aura.music.data.network.ResponseMeta
-keepclassmembers class com.aura.music.data.network.ResponseMeta {
    public synthetic <init>(java.lang.String,boolean,java.util.Map,java.lang.String,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
