# 서현역 AK플라자 (07169) BLE 버스 도착 알리미

## 1. GitHub Actions로 설치용 APK 자동 빌드하는 방법 (Android Studio 불필요)
1. GitHub에서 새 저장소(Repository)를 하나 생성합니다 (Public 또는 Private 모두 가능).
2. 이 ZIP 파일의 압축을 푼 뒤, 내부의 **모든 파일과 숨김 폴더(`.github`)**를 저장소에 업로드(Commit)합니다.
3. GitHub 저장소 상단의 **Actions** 탭으로 이동합니다.
4. `Build Android APK` 워크플로우가 자동으로 실행되며 약 2~3분 후 완료됩니다.
5. 완료된 워크플로우를 클릭한 뒤 하단 **Artifacts** 영역에서 `SeohyeonBusNotifier-debug-apk`를 다운로드합니다.
6. 압축을 풀면 나오는 `app-debug.apk`를 스마트폰으로 옮겨 설치합니다.

## 2. 앱 사용 방법
1. 앱 실행 후 공공데이터포털(`data.go.kr`)에서 발급받은 **경기도_버스도착정보 조회(Decoding 키)**를 입력합니다.
2. **[설정 저장 및 백그라운드 감지 시작]** 버튼을 누르고 블루투스/알림 권한을 허용합니다.
3. 화면이 꺼진 상태에서도 안정적으로 동작하도록 **[배터리 최적화 제외 설정]** 버튼을 눌러 허용해 줍니다.
4. 이제 폰 화면을 끄고 주머니에 넣은 채로 `LILYGO T-Display-S3`의 **IO14 버튼**을 누르면, 보드가 깨어나며 522-2번과 521번 실시간 도착 시간과 달리는 버스 애니메이션이 30초간 표시됩니다.

## 3. T-Display-S3 펌웨어 업로드
- `firmware/T_Display_S3_Bus_BLE.ino` 파일을 Arduino IDE로 열어 업로드하세요.
- `TFT_eSPI` 라이브러리 설치 후 `User_Setup_Select.h`에서 `#include <User_Setups/Setup206_LilyGo_T_Display_S3.h>` 주석을 해제해야 화면이 정상 출력됩니다.
