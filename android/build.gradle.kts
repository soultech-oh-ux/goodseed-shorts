plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

// 바탕화면(동기화·검색 색인이 파일을 붙잡는 경우)에서 빌드 폴더가 잠기면 GS_BUILD_ROOT 로 빌드 산출물 위치를 옮긴다.
System.getenv("GS_BUILD_ROOT")?.let { root ->
    subprojects { layout.buildDirectory.set(file("$root/${project.name}")) }
}
