# Watch `gain/vehicle` 응답 구조

> 공개용으로 정리된 예시다. 실제 차량 식별정보와 Bluetooth 키 재료는 포함하지 않는다.

## 수집 정보

- API: `watch/login/gain/vehicle` (개발 테스트 화면 4번: Gain Vehicle)
- 검증 지역: `KR`
- 검증 차량 계열: BYD ATTO 3

## BLE 관련 결론

`cfVechicle.watchBluetoothDto`가 한국 Watch API에서 제공하는 BLE 정보의 위치다.

| 필드 | 공개용 예시 | 의미 |
| --- | --- | --- |
| `functionCode` | `3001`, `3002`, `3005`, `3000` | 차량이 지원하는 Watch BLE 기능 코드 |
| `macAddress` | `AA:BB:CC:DD:EE:FF` | 서버가 지정한 차량 BLE MAC 주소 |
| `watchBluetoothInfo.dkey` | `<REDACTED_DKEY>` | 차량별 BLE 인증 키 재료. 로그나 문서에 기록하면 안 됨 |
| `watchBluetoothInfo.keyNumber` | `0` | BLE 키 번호 |
| `watchBluetoothInfo.keyValidTo` | `<EPOCH_MILLISECONDS>` | 키 유효 종료 시각 |

이 응답과 별도 `gain/bluetooth` 응답에는 한국 `dkey` 경로에서 구형 KA 인증용 계정 비밀번호가 필요하지 않았다. 현재 퓨어 Java 코덱은 이 한국 `dkey` 인증 방식만 실제 차량에서 검증했다.

## 구현 반영

앱은 응답의 `watchBluetoothDto.macAddress`를 저장하고 이후 BLE 연결에서 서버가 지정한 MAC을 우선 사용한다. `dkey`, VIN, 차량 번호, 사용자 식별자 및 토큰은 공개 문서나 테스트 벡터에 복사하지 않는다.

## 축약된 공개용 응답 예시

```json
{
  "energyType": 0,
  "cfVechicle": {
    "cfFixedList": [
      {"code": "Locking", "functionNo": "1005"},
      {"code": "Unlocking", "functionNo": "1006"},
      {"code": "Open the trunk", "functionNo": "1020"}
    ],
    "vehicleFunLearnInfo": {
      "rudderType": 1,
      "trunkLearnInfo": 1
    },
    "watchBluetoothDto": {
      "functionCode": ["3001", "3002", "3005", "3000"],
      "macAddress": "AA:BB:CC:DD:EE:FF",
      "watchBluetoothInfo": {
        "dkey": "<REDACTED_DKEY>",
        "keyNumber": 0,
        "keyValidTo": "<EPOCH_MILLISECONDS>"
      }
    }
  },
  "autoPlate": "00가0000",
  "modelNameOut": "ATTO 3"
}
```
