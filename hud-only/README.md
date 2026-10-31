# HUD Only — untitled HUD 분리 프로젝트

기준: ichigoproduction/untitled `master` @ c2f35d69f497537377aad7ea2b5f8c6d2995fb3d

## 포함
- `/hud`, `/hud reset`: Food, Drink, Syringe HUD 드래그 위치 편집
- `/fs`: 음식 스택 HUD (기존 FoodStack.java 로직 유지)
- `/ds`: 음료 스택 HUD (동일 파일)
- `/ss`: 주사기 60초 쿨타임 HUD

## 제외
`/sh` 스킬 HUD, `/party`, Title HUD, Glow, Skin, iModel, Optimizer, 다른 모든 untitled 기능. 별도의 Mixin을 사용하지 않습니다.

## 빌드
Minecraft 1.21.4, Fabric, Java 21, Gradle 8.13 기준.
이 디렉터리에서 `gradle clean build`를 실행하거나 GitHub Actions의 'Build HUD Only'를 이용합니다.
배포 JAR: `build/libs/hudonly-1.0.3.jar`. `-sources.jar`가 아닌 파일을 사용하세요.

## 설정
독립적인 `config/hudonly.json`으로 저장합니다. 기존 `config/152_hud.json`은 변경하지 않습니다.
기존 설정을 복사하려면 먼저 백업하고, 필요한 food/syringe 설정만 수동 이관하세요.

## 설치 주의
기존 `untitled.jar`와 동시에 설치하지 마세요. `/hud`, `/fs`, `/ds`, `/ss` 명령 및 메시지 감지가 중복될 수 있습니다.

## 원본과의 차이
`EditHud.java`에서 Party, Skill, Title 연동만 제거했습니다.
`FoodStack.java`와 `SyringeCooldownHud.java`의 기능 로직은 패키지 변경 외에 수정하지 않았습니다.
