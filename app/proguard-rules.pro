# AIDL 인터페이스는 이름이 살아있어야 함 (Shizuku 서비스 바인딩용)
-keep interface com.mttd.IUserService { *; }
-keep class com.mttd.IUserService$Stub { *; }
-keep class com.mttd.service.UserService { *; }
-keep class com.mttd.service.UserService$* { *; }

# direct 플레이버: mttd_starter(app_process, jni/mttd_starter.cpp)가 리터럴 클래스명 문자열로
# CLASSPATH=base.apk 에서 직접 로드하는 진입점 — 매니페스트/일반 코드 참조가 없어 R8이 도달
# 불가로 보고 이름을 지워버린다. 실기기(Android 16)에서 이것 때문에 데몬이 뜨자마자
# ClassNotFoundException 으로 죽어, Binder 핸드오프(DirectAdbManager.handleDaemonBinder)가
# 영원히 안 일어나고 앱이 adb TCP 연결에 계속 의존하는 상태로 남았다 — WiFi 를 끄면(LTE 전환
# 등) 그 TCP 연결이 끊기면서 "연결이 끊긴다"는 제보로 이어졌다.
-keep class com.mttd.data.adb.starter.DirectDaemonStarter { *; }

# Shizuku
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# Protobuf-lite — GeneratedMessageLite 는 리플렉션으로 필드를 찾아 스키마를 구성한다.
# (newMessageInfo 의 필드 문자열이 실제 필드명을 가리킴). R8이 필드명을 바꾸면
# "Field seasonId_ for X not found" 런타임 예외로 릴리스 빌드에서만 파싱이 깨진다.
-keep class com.mttd.proto.price.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
