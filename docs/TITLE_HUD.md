# Title / Subtitle HUD 제어 (/th)

Minecraft **1.21.4 Fabric 클라이언트 전용** 기능입니다. 서버의 Title·Subtitle 텍스트 패킷 자체는 변경하지 않고, 화면 중앙에 표시되는 **기본 Title와 Subtitle 렌더링만** 제어합니다. 채팅, 액션바, 핫바 등 다른 영역에는 영향을 주지 않습니다.

## 명령어

| 명령어 | 기능 |
|---|---|
| `/thud` | **Title/Subtitle 전용 드래그 편집 화면** 열기 (기존 `/hud`와 독립) |
| `/thud reset` | Title와 Subtitle 두 위치만 기본값으로 초기화 |
| `/th toggle` | Title와 Subtitle을 **함께** 켜거나 끄기 |
| `/th title toggle` | Title만 켜기/끄기 |
| `/th subtitle toggle` | Subtitle만 켜기/끄기 |
| `/th size 0.5` | Title·Subtitle 둘 다 크기 배율 0.5 지정 |
| `/th title size 0.5` | Title만 크기 배율 0.5 지정 |
| `/th subtitle size 0.8` | Subtitle만 크기 배율 0.8 지정 |

- 크기 범위는 **0.1~5.0**, 기본 배율은 **1.0**(기본 Minecraft Title 4배, Subtitle 2배 크기)입니다.
- `/th toggle`은 두 항목 중 **하나라도 보이는 상태**에서 사용하면 둘 다 숨기고, 둘 다 숨긴 상태에서 사용하면 둘 다 표시합니다.
- 어떤 명령어도 채팅이나 액션바에 별도의 성공·변경 메시지를 보내지 않습니다. Minecraft 자체의 잘못된 명령어 오류와 권한 오류까지 숨기는 기능은 아닙니다.
- `/thud` 화면은 Title과 Subtitle을 각각 드래그하고 **ESC로 종료 및 저장**합니다. HUD에서 둘 다 꺼져 있어도 미리보기 문구는 편집할 수 있습니다.
- 제목·부제의 서버 설정 표시 시간/페이드 인/페이드 아웃은 보존합니다. 특정 문구만 필터링하는 기능은 제공하지 않습니다.
- 모든 설정은 `config/152_hud.json`에 저장되어 재시작 후에도 적용됩니다.

## 기술 메모

`TitleHudRenderMixin`은 `InGameHud.renderTitleAndSubtitle(DrawContext, RenderTickCounter)`만 가로채고, 그 외 렌더 메서드는 건드리지 않습니다. Title과 Subtitle의 좌표·크기·표시 여부를 독립적으로 적용하며 1.21.4 기존 타이머 기반 alpha fade를 재현합니다.

실제 Minecraft 구동 및 서버 게임 화면 재현은 별도 확인이 필요합니다.
