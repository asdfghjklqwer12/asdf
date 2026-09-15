pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Android/AndroidX 아티팩트만 여기서 찾는다. 필터가 없으면 모든 의존성이
        // 먼저 google() 을 거치면서 불필요한 왕복이 생긴다.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "study-streak"

// 순수 Kotlin 규칙 엔진. Android SDK 없이 빌드·테스트된다.
include(":domain")

// 규칙을 60일 돌려보고 표로 찍어주는 실행기. 역시 Android 없이 돈다.
include(":sim")

// TODO(app): Compose UI 모듈. Android SDK 가 있는 환경에서 아래 줄을 켠다.
//   include(":app")
