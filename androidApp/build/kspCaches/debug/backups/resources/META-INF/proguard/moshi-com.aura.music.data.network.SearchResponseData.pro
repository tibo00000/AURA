-if class com.aura.music.data.network.SearchResponseData
-keepnames class com.aura.music.data.network.SearchResponseData
-if class com.aura.music.data.network.SearchResponseData
-keep class com.aura.music.data.network.SearchResponseDataJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.aura.music.data.network.SearchResponseData
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.aura.music.data.network.SearchResponseData
-keepclassmembers class com.aura.music.data.network.SearchResponseData {
    public synthetic <init>(java.lang.String,com.aura.music.data.network.BestMatch,java.util.List,java.util.List,java.util.List,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
