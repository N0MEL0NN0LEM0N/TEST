#include <TFT_eSPI.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include "esp_sleep.h"

#define BUTTON_PIN GPIO_NUM_14
#define DISPLAY_DURATION_MS 30000 // 30초 동안 화면 유지 후 슬립

#define SERVICE_UUID "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHAR_RX_UUID "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"

TFT_eSPI tft = TFT_eSPI();
TFT_eSprite sprite = TFT_eSprite(&tft);

String time522_1 = "..", time522_2 = "..";
String time521_1 = "..", time521_2 = "..";
bool dataReceived = false;
unsigned long wakeStartTime = 0;

class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pChar) override {
    std::string rxValue = pChar->getValue();
    if (rxValue.length() > 0) {
      String data = String(rxValue.c_str()); // 예: "522-2:3,14|521:6,-"
      int sep = data.indexOf('|');
      if (sep != -1) {
        String p1 = data.substring(6, sep);
        String p2 = data.substring(sep + 5);

        int c1 = p1.indexOf(',');
        time522_1 = p1.substring(0, c1);
        time522_2 = p1.substring(c1 + 1);

        int c2 = p2.indexOf(',');
        time521_1 = p2.substring(0, c2);
        time521_2 = p2.substring(c2 + 1);

        dataReceived = true;
      }
    }
  }
};

void drawBusIcon(int x, int y, uint16_t color, int wheelOffset) {
  sprite.fillRoundRect(x, y, 42, 22, 4, color);
  sprite.fillRect(x + 4, y + 4, 10, 8, TFT_BLACK);
  sprite.fillRect(x + 17, y + 4, 10, 8, TFT_BLACK);
  sprite.fillRect(x + 30, y + 4, 8, 10, TFT_CYAN);
  sprite.fillCircle(x + 10, y + 23 + wheelOffset, 4, TFT_WHITE);
  sprite.fillCircle(x + 32, y + 23 - wheelOffset, 4, TFT_WHITE);
}

void enterDeepSleep() {
  BLEDevice::getAdvertising()->stop();
  tft.fillScreen(TFT_BLACK);
  digitalWrite(TFT_BL, LOW);
  esp_sleep_enable_ext0_wakeup(BUTTON_PIN, 0);
  esp_deep_sleep_start();
}

void setup() {
  pinMode(BUTTON_PIN, INPUT_PULLUP);

  tft.init();
  tft.setRotation(1);
  tft.fillScreen(TFT_BLACK);
  sprite.createSprite(320, 170);

  BLEDevice::init("T-Display-Bus");
  BLEServer *pServer = BLEDevice::createServer();
  BLEService *pService = pServer->createService(SERVICE_UUID);
  BLECharacteristic *pRxChar = pService->createCharacteristic(
    CHAR_RX_UUID, BLECharacteristic::PROPERTY_WRITE
  );
  pRxChar->setCallbacks(new RxCallbacks());
  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->start();

  wakeStartTime = millis();
}

void loop() {
  unsigned long elapsed = millis() - wakeStartTime;
  if (elapsed >= DISPLAY_DURATION_MS) {
    enterDeepSleep();
  }

  sprite.fillSprite(TFT_BLACK);

  // 상단 헤더
  sprite.fillRect(0, 0, 320, 26, 0x18E3);
  sprite.setTextColor(TFT_WHITE, 0x18E3);
  sprite.setTextSize(2);
  sprite.drawString("SEOHYEON AK (07169)", 10, 5);

  // 30초 카운트다운 프로그레스 바
  int barWidth = map(DISPLAY_DURATION_MS - elapsed, 0, DISPLAY_DURATION_MS, 0, 320);
  sprite.fillRect(0, 26, barWidth, 3, TFT_GREENYELLOW);

  // 522-2번 버스
  sprite.setTextColor(TFT_GREEN, TFT_BLACK);
  sprite.setTextSize(3);
  sprite.drawString("522-2", 12, 42);

  sprite.setTextColor(TFT_WHITE, TFT_BLACK);
  String disp522 = dataReceived ? (time522_1 + "m  (" + time522_2 + "m)") : "Waiting BLE...";
  sprite.setTextSize(2);
  sprite.drawString(disp522, 125, 47);

  // 521번 버스
  sprite.setTextColor(TFT_GREEN, TFT_BLACK);
  sprite.setTextSize(3);
  sprite.drawString("521", 12, 85);

  sprite.setTextColor(TFT_WHITE, TFT_BLACK);
  String disp521 = dataReceived ? (time521_1 + "m  (" + time521_2 + "m)") : "Waiting BLE...";
  sprite.setTextSize(2);
  sprite.drawString(disp521, 125, 90);

  // 도로 및 달리는 버스 애니메이션
  sprite.drawLine(0, 158, 320, 158, TFT_DARKGREY);
  int busX = (millis() / 15) % 360 - 45;
  int bounce = (millis() / 150) % 2;
  drawBusIcon(busX, 130, TFT_GREEN, bounce);

  // 정류장 표지판
  sprite.fillRect(290, 128, 3, 30, TFT_LIGHTGREY);
  sprite.fillCircle(291, 125, 6, TFT_SKYBLUE);

  sprite.pushSprite(0, 0);
  delay(20);
}
