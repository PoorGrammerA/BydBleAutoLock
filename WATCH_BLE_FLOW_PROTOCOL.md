# BYD Watch BLE 인증 흐름과 페이로드

이 문서는 현재 앱의 `DevTestActivity`에 있는 1~6단계가 수행하는 일과, 한국 서버 및 차량 BLE와 교환하는 데이터의 형식을 정리한다. 예시의 UUID, VIN, 토큰, dkey, MAC 주소는 모두 마스킹한 값이다.

## 전체 흐름

```text
1. QR 생성 ──> 공식 BYD 앱에서 QR 승인
2. QR 상태 확인 ──> 승인 상태(codeStatus=2) 확인
3. Watch 토큰 획득 ──> encryToken/signToken/VIN 확보
4. 차량 정보 획득 ──> 차량 MAC, dkey, 지원 기능 저장
5. Bluetooth 키 획득 ──> dkey/key 번호 보강
6. 차량과 BLE 연결/인증 ──> READY 상태에서 제어 명령 전송
```

앱을 다시 시작해도 3~5단계의 결과는 로컬 저장소에 남는다. 차량·키 정보가 유효하면 6번만 다시 실행할 수 있다. 앱 데이터 삭제, 재설치, 차량 변경 또는 키 갱신이 필요한 경우에는 1번부터 다시 수행한다.

## 공통 HTTP 형식

### 전송 형식

Watch API는 일반 스마트폰 앱의 Bangcle 외피(`{"request":"F..."}`)를 사용하지 않는다. HTTP 본문으로 아래와 같은 **일반 JSON**을 직접 전송한다.

```json
{
  "countryCode": "KR",
  "encryData": "AES_HEX...",
  "identifier": "KR 또는 사용자 식별자",
  "reqTimestamp": "1760000000000",
  "sign": "SHA1_MIXED...",
  "watchImei": "MD5_HEX...",
  "watchModel": "SM-R925N"
}
```

응답은 한 번 문자열로 감싼 `response` 필드를 사용한다.

```json
{
  "response": "{\"code\":\"0\",\"message\":\"SUCCESS\",\"respondData\":\"AES_HEX...\"}"
}
```

`response`를 JSON으로 다시 해석한 뒤 `code`가 `0`인지 확인한다. 성공 시 `respondData`를 AES 복호화해 실제 업무 JSON을 얻는다.

### 암호화와 서명

- `encryData`, `respondData`: AES-128-CBC, PKCS#5/7 padding, IV는 16바이트 `0x00`.
- 암호화 키: 입력 문자열의 MD5 hex를 16 raw byte key로 변환한다.
  - 비로그인 요청/응답: `MD5(countryCode)` (`KR`).
  - 로그인 후 요청/응답: `MD5(encryToken)`.
- `sign`: 정렬된 `key=value&` 문자열의 끝에 `password=MD5(서명 원본)`을 붙인 뒤, 프로젝트의 `sha1Mixed` 규칙으로 계산한다.
- 이 흐름의 HTTP 처리에는 Bangcle table이나 `libencrypt.so`가 필요하지 않으며, Java `CryptoUtils`로 처리한다.

## 단계별 역할과 페이로드

### 1. Create QR

- 엔드포인트: `POST /watch/login/create/qrcode`
- 역할: 휴대폰의 공식 BYD 앱이 승인할 일회용 UUID를 생성한다.
- 비로그인 요청이다. 암호화 전 내부 데이터는 다음과 같다.

```json
{
  "timeStamp": "1760000000000",
  "random": "UPPERCASE_UUID_WITHOUT_DASHES",
  "networkType": "wifi",
  "version": "앱 버전 코드"
}
```

- 대표 응답 업무 데이터:

```json
{
  "uuid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
}
```

- 저장값: `watch_qr_uuid`.
- 화면 동작: UUID로 `watchQRCode://<AES-HEX>` 형식의 QR을 생성해 표시한다. QR 원문은 `watchImei=<...>&uuid=<...>&countryCode=KR`이다.

### 2. Check QR Status

- 엔드포인트: `POST /watch/login/check/qrcode`
- 역할: 공식 BYD 앱에서 QR 승인/거부/만료 여부를 확인한다.
- 암호화 전 내부 데이터:

```json
{
  "timeStamp": "1760000000000",
  "random": "UPPERCASE_UUID_WITHOUT_DASHES",
  "networkType": "wifi",
  "version": "앱 버전 코드",
  "uuid": "1번에서 받은 UUID"
}
```

- 대표 응답 업무 데이터:

```json
{
  "codeStatus": "2",
  "appChannel": "선택 값"
}
```

- `codeStatus = 2`가 승인 완료다. `3` 또는 `4`는 거부/만료로 처리한다.
- 이 앱의 버튼은 한 번 조회한다. 자동 흐름의 `WatchBleKeyFlowManager`를 사용할 경우에는 3초 간격으로 최대 150초 동안 조회한다.

### 3. Gain Token

- 엔드포인트: `POST /watch/login/gain/token`
- 역할: 승인된 UUID를 Watch 세션 토큰과 VIN으로 교환한다.
- 암호화 전 내부 데이터:

```json
{
  "timeStamp": "1760000000000",
  "timeZone": "Asia/Seoul",
  "uuid": "1번에서 받은 UUID"
}
```

- 대표 응답 업무 데이터:

```json
{
  "watchTokenInfo": {
    "identifier": "사용자 식별자",
    "userType": "0",
    "vin": "차량 VIN",
    "encryToken": "암호화 토큰",
    "signToken": "서명 토큰"
  },
  "controlPwd": "선택 값"
}
```

- 저장값: `encryToken`, `signToken`, `identifier`, `userType`, `vin`.
- 이후 4·5번 요청은 로그인 요청 형식을 사용한다.

### 4. Gain Vehicle

- 엔드포인트: `POST /watch/login/gain/vehicle`
- 역할: 차량 기본 정보, 서버가 지정한 차량 BLE MAC, Watch Bluetooth 키, 원격 기능·차량 기능 지원 목록을 가져온다.
- 암호화 전 내부 데이터:

```json
{
  "timeStamp": "1760000000000",
  "random": "UPPERCASE_UUID_WITHOUT_DASHES",
  "watchImei": "MD5_HEX...",
  "deviceType": "0",
  "networkType": "wifi",
  "appVersion": "2",
  "vin": "차량 VIN"
}
```

- 대표 응답 업무 데이터:

```json
{
  "modelNameOut": "ATTO 3",
  "autoPlate": "01훗1234",
  "energyType": 0,
  "cfVechicle": {
    "vehicleFunLearnInfo": {
      "rudderType": 1,
      "trunkLearnInfo": 1
    },
    "cfFixedList": [
      {"code": "Locking", "functionNo": "1005"},
      {"code": "Unlocking", "functionNo": "1006"}
    ],
    "watchBluetoothDto": {
      "macAddress": "E8:CD:**:**:**",
      "functionCode": ["3001", "3002", "3005", "3000"],
      "watchBluetoothInfo": {
        "dkey": "32_HEX_CHARACTERS...",
        "keyNumber": 0,
        "keyValidTo": 4102415999000
      }
    }
  }
}
```

- 현재 차량 표기 기준:
  - `modelNameOut`: 차종.
  - `autoPlate`: 차량 번호.
  - `energyType = 0`: 순수 전기차(EV).
  - `rudderType = 1`: 좌핸들.
- 저장값:
  - 전체 응답 JSON (`watch_vehicle_info_json`, Android Keystore 기반 암호화 저장).
  - `cfVechicle.watchBluetoothDto.macAddress` → 차량 BLE MAC.
  - `cfVechicle.watchBluetoothDto.watchBluetoothInfo.dkey`, `keyNumber` → BLE 인증 재료.
- `cfFixedList`: 서버/공식 앱이 노출할 수 있는 기능 목록이다. 모든 번호가 로컬 BLE 명령 번호는 아니다.
- `vehicleFunLearnInfo`: 차량별 기능 지원/구성 코드다. 앱에서는 Vehicle Health 및 지원 정보 텍스트로 표시한다.

### 5. Gain Bluetooth Key

- 엔드포인트: `POST /watch/login/gain/bluetooth`
- 역할: Bluetooth 키 재료를 다시 가져와 4번의 데이터를 보강하거나 갱신한다.
- 암호화 전 내부 데이터: 4번과 같으며 `appVersion = 2`, `vin`을 포함한다.
- 대표 응답 업무 데이터:

```json
{
  "dk": "32_HEX_CHARACTERS...",
  "empowerBluetoothKeyNo": 0,
  "bluetoothMacAddress": "선택 값",
  "authBluetoothProtocol": "선택 값",
  "blueToothPassword": "선택 값"
}
```

- 한국 차량에서는 `gain/bluetooth` 응답이 MAC 또는 구형 비밀번호를 생략할 수 있다. 이 경우 4번의 `watchBluetoothDto`에서 저장한 MAC·dkey·keyNumber를 사용한다.
- 저장값: `dkey`, key 번호, 프로토콜·MAC이 응답에 존재하면 해당 값.

### 6. Connect & Authenticate BLE

이 단계는 HTTP 요청이 아니라 차량과의 로컬 GATT 통신이다.

- 대상: 4번에서 서버가 준 차량 BLE MAC.
- GATT: 차량 서비스의 write characteristic으로 프레임을 보내고 notify characteristic으로 응답을 받는다.
- 캡처에서 확인한 프레임은 20 byte 단위이며 `F5 FA` 종료 표식을 사용한다.
- 한국 차량은 `dkey`가 있으면 새 키(new-key) 인증 경로를 사용한다. 전화번호나 로그인 비밀번호를 차량으로 보내지 않는다.

새 키 인증 순서:

```text
GATT 연결
  → Wake-up frame
  → 200ms 후 App random 교환 frame (keyNumber 포함)
  ← 차량 random 응답 (CRC 및 keyState=1 확인)
  → dkey 기반 Authentication frame
  ← 인증 결과 1
  → READY
```

`READY` 이후에만 차량 제어 프레임을 보낸다. 한국 ATTO 3에서 확인된 로컬 BLE 동작은 잠금, 전체 문 잠금 해제, 트렁크 열기, 라이트 점멸 및 라이트+경적이다. 공조, 창문, 트렁크 닫기, 시동 관련 매핑은 현재 퓨어 Java 구현에서 검증되지 않았다. `cfFixedList.functionNo`는 원격 앱 기능 번호이므로, 확인된 매핑 없이 그대로 BLE로 전송하면 안 된다.

제어 명령 뒤 차량은 다음 20바이트 응답 프레임을 notify characteristic으로 보낸다.

```text
A5 5A 24 E5 <control-code> <result> <vehicle-state...> <CRC> F5 FA
```

- `0x24`: 제어 명령 응답.
- `0xE5`: 차량 제어 명령 종류.
- `control-code`: 요청한 잠금 해제 `0x05`, 잠금 `0x07`, 트렁크 열기 `0x06`, 라이트 `0x16` 등과 일치해야 한다.
- `result = 0x01`: 차량이 성공으로 응답했다는 의미다.
- 앱은 GATT 쓰기 완료 후 최대 3초간 일치하는 응답을 기다린다. 다른 결과는 차량 거부로 처리하고, 시간 초과는 실제 동작 여부를 알 수 없는 `확인 불가`로 처리한다.
- 시간 초과 시 차량이 이미 동작했을 수 있으므로 REST로 자동 재전송하지 않는다. GATT 쓰기 실패나 명시적인 차량 거부는 기존 REST 폴백 대상이다.

현재 실제 차량 검증 범위는 한국 Watch API의 `dkey` 인증 방식뿐이다. 지역 선택 목록의 다른 서버는 실험적이며 지원이 보장되지 않는다. 구형 user-ID/password/KA BLE 인증 경로는 앱에 포함하지 않는다.

## 로컬 저장과 재시작

- UUID, Watch 토큰, 차량 응답, dkey, key 번호와 서버 차량 MAC은 `StorageManager`에 저장한다.
- 민감 문자열은 Android Keystore AES-GCM을 사용해 저장한다.
- 재시작 뒤에는 저장된 차량 MAC과 dkey가 있으면 6번만 실행할 수 있다.
- 앱 데이터 삭제, 재설치 또는 키 만료/차량 변경 시 1~5번을 다시 실행한다.

## 보안 주의사항

- `encryToken`, `signToken`, `dkey`, VIN, 차량 번호, BLE MAC은 민감 정보다.
- 디버그 로그에 전체 복호화 응답이 남을 수 있으므로 공유 전 반드시 마스킹한다.
- 이 문서의 기능 매핑은 현재 한국 차량 캡처와 구현을 기준으로 한다. 다른 국가·차종에서는 서버 응답과 BLE 프로토콜이 다를 수 있다.
