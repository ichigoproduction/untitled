# 모드가 설치돼 보이지만 모든 기능이 없는 경우

이 저장소는 **Minecraft 1.21.4 전용 Fabric 클라이언트 모드**입니다. Minecraft 26.x, 바닐라 또는 Fabric API가 없는 인스턴스에서는 정상 동작을 기대할 수 없습니다.

## 빌드
- Java 21에서 Windows: `gradlew.bat clean build` (Gradle Wrapper 실행 파일이 있는 경우) / 설치된 Gradle로 `gradle clean build`
- `build` 작업에 `verifyModJar`가 추가됩니다. remapped 실행용 JAR가 필요한 클라이언트 클래스, `fabric.mod.json`, Mixin 설정, 스킬 카탈로그를 포함하지 않으면 빌드가 실패합니다.
- 설치 대상은 `build/libs/untitled-1.0.3.jar`처럼 **버전명만 붙은 실행용 JAR**입니다. `-sources.jar`(소스 코드) 또는 `-dev.jar`(개발용)는 일반 게임 `mods` 폴더에 설치하지 마세요.
- 실제로 실행하는 게임 인스턴스의 `mods` 폴더에서 이전 버전의 `untitled` JAR를 제거하고 실행용 JAR 하나만 사용하세요.

## 실행 진단
`logs/latest.log`에 아래 두 로그가 출력되도록 했습니다.

```text
untitled client initialization started
untitled client initialization finished: HUDs, commands, models and inspectors registered
```

- **첫 로그가 없다면:** Fabric 클라이언트 엔트리포인트가 실행되지 않은 것입니다. Minecraft 버전/로더, JAR 경로 및 설치한 파일을 확인하세요.
- **시작 로그만 있다면:** 초기화가 중단됐을 가능성이 있으니 다음 오류 로그를 확인하세요.
- **두 로그 모두 있다면:** 모듈 초기화가 수행됐다는 뜻입니다. 서버 또는 월드 진입 후 `/fs`, `/ds`, `/sh`, `/ss`, `/party`, `/hud` 명령어 등록 여부를 확인하세요.

이 검증과 로그 추가로 **빌드 산출물 누락이나 클라이언트 초기화 실패를 구분**할 수 있지만, 사용자의 실제 Minecraft 로그 및 설치된 JAR가 없으므로 현재 증상의 원인이 확정된 것은 아닙니다.
