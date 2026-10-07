# FullCaliberApModel + RHA baseline — 설계 제안 v2 (코드 없음, 리뷰용)

상태: **제안 v2**. 승인 전에는 solver 코드와 보정 수치를 쓰지 않는다.
전제: traversal API는 동결이며 이 모델은 `PenetratorModel.solve(ImpactContext, ArmorLayer): PenetrationResult` 안에서만 동작한다.
근거: 모든 사실 주장은 `full-caliber-ap-sources.md`(출처 목록)에 있고, 거기서 **PRIMARY**로 확인된 것만 사실로 쓴다. 아래 `[S1]`~`[S5]`, `[D1]`~`[D5]`는 그 문서의 항목이다.

## 0. v1에서 무엇이 바뀌었나

| # | v1 | v2 | 이유 |
|---|---|---|---|
| 1 | 지수 `a,b,c`와 `v_ref`를 데이터로 적합 | **de Marre 지수를 고정**(`DeMarreProfile`)하고 **저항 계수 K 하나만** 적합 | 리뷰 결정 1. 후보 데이터가 단일 탄·단일 직경이라 지수를 적합할 근거가 없다(5절). |
| 2 | 유효 구간 밖은 클램프/외삽 선택 | **클램프 금지.** `IN_RANGE / EXTRAPOLATED / OUT_OF_MODEL` | 리뷰 결정 3 |
| 3 | `v_bl := V50 완전관통` | `BallisticLimitDefinition`을 **데이터로 명시**하고 서로 환산하지 않음 | 리뷰 수정 1. D1은 PROTECTION_LIMIT |
| 4 | "Recht–Ipson / Lambert–Jonas 형태" 한 식 | **서로 다른 잔류속도 전략**으로 분리. 첫 버전은 둘 다 아닌 **명시적 에너지 균형 기준선** | 리뷰 수정 2·4. 원 논문을 읽지 못했다(출처 S4, S5) |
| 5 | 플러그 질량 `ρ·π/4·d²·T_los` 제안 | **삭제** | 리뷰 수정 3. 출처상 전단 플러깅·소구경 맥락 |
| 6 | 경사 처리 `M(θ)` 표 또는 `sec^n` 포함 | **v1은 수직 충돌만.** 경사는 별도 단계 | 비피모 AP의 경사 데이터가 없다. `sec⁴`는 D2와 맞지 않는다 |
| 7 | 도탄/파쇄 곡선 사용 | **v1은 만들지 않음** | 출처 있는 곡선이 없다 |
| 8 | `provenance` 선택 필드(문자열) | **`CalibrationProvenance` 구조체** | 리뷰 결정 6 |
| 9 | "RHA 기준" 규격 미정 | **`RHA_REFERENCE` = MIL-DTL-12560 호환 균질압연장갑**, 경도·두께·클래스를 provenance에 기록 | 리뷰 결정 5. 경도는 두께에 따라 다르다 [S3 Table II] |
| 10 | 물리/밸런스 구분 없음 | **물리 결과와 게임플레이 정책을 분리** | 리뷰 결정 4 |

## 1. 범위 (이번 구현이 *하는* 것)

**전구경(full-caliber) 비피모 AP 탄 × MIL-DTL-12560 호환 RHA × 수직 충돌.**

- 근거 데이터: [D1] MIL-DTL-12560K Table A-VII, 90 mm M318A1 AP, 0°, 두께 3.94–6.06 in. 이 탄이 비피모(알루미늄 윈드실드만 있음)라는 것은 **2차 요약으로만 확인**했고 질량은 확인하지 못했다. 둘 다 구현 전에 확인해야 한다.
- 하지 않는 것: APC, APCBC, APCR/APDS, APFSDS, HEAT/HESH, 표면경화·복합 장갑, 경사 충돌, 도탄, 파쇄, 부분관통, 마모, 플러그/spall 생성.
- **AP / APC / APCBC를 하나의 "DeMarre 탄"으로 합치지 않는다.** 프리셋은 `projectile_family`를 가지며(`AP_UNCAPPED` 등), solver는 정의에서 가족이 다르면 `OUT_OF_MODEL`로 거부한다.

## 2. 식

### 2.1 탄도한계속도 (수직 충돌)

```
v_bl = K · V_dM(d, T, m)

V_dM(d, T, m) = C_SI · d^p_d · T^p_T · m^(−p_m)
```

`DeMarreProfile`(데이터): `diameterExponent = p_d`, `thicknessExponent = p_T`, `massExponent = p_m`, `baselineConstant`(단위 명시).
- **첫 production 프리셋의 프로파일은 1937년 해군 교범 식 (2), 니켈강용 값**: `p_d = 0.75`, `p_T = 0.70`, `p_m = 0.50` [S1]. 코드에 매직 상수로 넣지 않고 프리셋 데이터로 둔다.
- `baselineConstant`도 [S1]의 공개 상수 `10^3.00945`(단위 ft/s, in, in, lb)를 **단위와 함께** 저장하고 로딩 시 SI로 변환한다. 변환 결과는 `C_SI = 0.3048 · 10^3.00945 · 0.0254^(−1.45) · 0.45359237^(0.5) ≈ 4.3131×10⁴` (SI: d, T [m], m [kg] → v [m/s]). 이 변환은 [S1] Problem II(탄 10 in, 500 lb, 15.77 in → 1772 ft/s)를 SI로 재현한다(540.09 m/s = 1771.93 ft/s). **테스트가 이 값을 상수에서 다시 계산하므로 문서의 값은 참고용이다.**
- `K`: 역사적으로는 [S1]의 판 성능계수(니켈강 기준선 K=1)지만, **DNCity 코드에서는 `calibrationCoefficient`**다. 이 값은 장갑 저항, 탄 구조, 한계 정의 불일치, 시험 방법, 모델 오차를 **모두** 흡수하므로 `armorCoefficient` 같은 이름을 쓰지 않으며(로더가 `armor_coefficient` 필드를 거부), `provenance` 없이는 로드되지 않는다. 따라서 **보정에 쓴 탄과 비슷한 탄에서만 의미가 있다**(5절).
- 결정 1에 따라 `K` 하나만 데이터로 적합한다. 적합은 `ln K = mean( ln v_bl,data − ln V_dM )`(로그 공간 최소제곱, 지수 고정).
- **D1에서 `K`를 지금 정하지 않는다.** M318A1 질량을 확인하지 못했다. 질량 없이 `K`를 말하면 임의의 숫자가 된다.
- 미리보기(보정 아님, 출처 문서 3절): 지수를 0.70으로 고정하고 계수 1개만 쓰면 D1 곡선(212행)을 −1.4 % … +3.2 %(rms 1.29 %)로 재현하고, 두께 지수를 자유 적합하면 0.80이다. 즉 **H0 고정은 좁은 두께 구간(≈ 3.9–5.1 in)에서 약 ±2 % 안**이고 구간 끝에서 체계적으로 휜다. 이 오차는 `rmse`로 provenance에 기록하고 `EXTRAPOLATED` 허용 폭 결정에 쓴다. 규격값(요구 최소)을 흉내 낸 오차일 뿐 독립 검증이 아니다.

### 2.2 잔류속도 — 전략을 분리한다

`ResidualVelocityModel`은 인터페이스(개념)이고 구현은 서로 다르다.

| 전략 | 식 | 상태 |
|---|---|---|
| `NO_PLUG_ENERGY_UPPER_BOUND` (**v1 기준선**) | `v_r = √(v_i² − v_bl²)` | 구현 대상 |
| `RECHT_IPSON` | `v_r = m/(m+m_p) · √(v_i² − v_bl²)` (2차 문헌의 서술, `m_p` 플러그 질량) | **원 논문 확인 전 보류** [S4] |
| `LAMBERT_JONAS` | `v_r = a (v_i^p − v_bl^p)^(1/p)` (2차 서술, `a`, `p`는 적합 계수) | **원 보고서 확인 전 보류** [S5] |

`NO_PLUG_ENERGY_UPPER_BOUND`는 위 두 모델이 **아니다**. 두 식의 `a = 1`, `p = 2` 극한과 수식이 같을 뿐이다. 이것을 쓰는 이유는 단 하나다: **규격 표는 한계속도만 주고 잔류속도 데이터가 없어서 어떤 plug/`a`/`p` 계수도 보정할 수 없기 때문이다.** 명시적 가정은 다음과 같다.
- 한계속도에서 탄이 잃는 에너지는 `½ m v_bl²`로 일정하고, 나머지는 잔류 운동에너지가 된다. 질량 불변, 변형 없음, 플러그 없음.
- 에너지 보존: `½ m v_i² = ½ m v_r² + ½ m v_bl²`. 에너지를 만들지 않는다(traversal이 검사).
- 플러그를 동반하는 실제 잔류속도보다 **잔류속도를 과대평가**한다(방향이 알려진 편향). 이 편향은 문서와 provenance `notes`에 남긴다.
- `p = 2`를 일반 법칙으로 주장하지 않는다. 위는 에너지 균형 가정이며 L–J의 `p`와 무관하다.
- `PenetrationResult`: `PERFORATED`(residual 속도 `v_r`, 방향 유지, 질량 불변), `depositedEnergyJ = ½ m v_bl²`, `spall = null`, `perforationPoint = 충돌점 + 방향 · T`(수직이므로 시선 두께 = 법선 두께). 위치는 traverser가 canonical하게 소유하므로 solver는 점만 반환한다.

### 2.3 도탄·파쇄·부분관통·마모

v1은 만들지 않는다. 출처 있는 곡선이 없다. 결과 outcome은 `PERFORATED` 또는 `STOPPED`뿐이다. `deformation`·`integrity`는 갱신하지 않으며, 이는 **알려진 한계**다.

## 3. 한계속도 정의 — `BallisticLimitDefinition`

프리셋과 모든 보정 데이터 점은 한계 정의를 가진다. **서로 다른 정의의 데이터는 환산하지 않고 별개 데이터셋으로 둔다.**

```
MINIMUM_PERFORATION   탄이 판을 통과하는 최소 속도
V50                   완전관통 확률 50 %의 속도 (MIL-STD-662F §3.40)
NAVY_LIMIT            해군 시험 관행의 한계 (정의는 출처마다 확인 필요)
PROTECTION_LIMIT      증인판 기준 V50BL(P) (MIL-STD-662F §3.8)
SOURCE_DEFINED        출처가 자체 정의를 쓰고 환산 불가
```

- [D1]은 **PROTECTION_LIMIT**이다(MIL-DTL-12560K A.2.6.2: 57 mm 이상은 완전관통 가장 낮은 2발과 부분관통 가장 높은 2발의 평균, 폭 100 ft/s 이하).
- 완전관통은 **탄이나 시험편의 파편**이 증인판을 뚫어도 성립한다(MIL-STD-662F §3.24). 그래서 이 한계는 "탄이 판을 통과하는 최소 속도"보다 **낮거나 같다.** 이 모델의 `PERFORATED`가 "탄 자체의 관통"인지 "판 뒤에 효과가 생김"인지는 **미해결 의미 충돌**이며 11절 결정 3이다. 프리셋은 어느 쪽인지 `ballistic_limit_definition` 필드로 명시해야 한다.
- [S1] de Marre 식의 "just penetrate"와 [S3]의 BL(P)는 같은 양이 아니다. `K`가 이 차이를 흡수하므로, K는 **정의와 묶어서만** 이식 가능하다.

## 4. 유효 구간 정책 — `IN_RANGE / EXTRAPOLATED / OUT_OF_MODEL`

**클램프는 하지 않는다.** 각 축마다 프리셋이 `valid_min/max`(보정 데이터에서 유도)와 `extrapolation_margin`을 가진다. 축은 `v_i`, `d/T`, `T`, `d`, `m`, `θ`, 탄 가족, 판 재질 클래스다.

| 판정 | 조건 | 동작 |
|---|---|---|
| `IN_RANGE` | 모든 축이 유효 구간 안 | 정상 계산 |
| `EXTRAPOLATED` | 어떤 축이 구간 밖이지만 그 축의 `extrapolation_margin` 안 | 계산하되 **진단(diagnostic)** 을 남긴다 |
| `OUT_OF_MODEL` | 마진 밖, 가족 불일치, 지원하지 않는 입력 | **solver가 결과를 만들지 않는다** |

- `extrapolation_margin`은 코드에 기본값을 두지 않는다. 프리셋 JSON이 값을 가져야 로드가 성공하고, 값은 홀드아웃 오차나 provenance의 `rmse`에서 근거를 남겨 정한다.
- v1의 D1 프리셋은 `θ`의 유효 구간이 **0**이다. 따라서 *모든 경사 충돌은 `OUT_OF_MODEL`*이다(2절 범위와 일치).
- `OUT_OF_MODEL` 처리 방식은 traversal API를 바꾸지 않는 선에서 다음 중 하나여야 한다. 11절 결정 2.
  - **결정 2 (확정): 예외를 쓰지 않는다.** `FullCaliberApModel.evaluate()`가 `ModelEvaluation.Unresolved`를 값으로 반환하고, solver chain/fallback은 (아직 없는) `BallisticsResolver`가 소유한다. 모델은 fallback을 스스로 고르지 않는다. `PenetratorModel.solve()`에는 "해석 불가"를 표현할 방법이 없으므로 이 모델은 아직 `PenetratorModel`로 등록하지 않는다.
- 공개 메서드 `supports(context, layer): Regime`를 두어 서버가 미리 판정할 수 있게 한다. 내부적으로 `solveDetailed()`가 판정과 진단을 함께 반환하고 `solve()`는 그것을 감싸는 얇은 래퍼다.

## 5. 이 데이터로 *말할 수 있는 것과 없는 것*

**[D1]은 한 종류의 탄(90 mm M318A1) 한 장(수직) 이다.** 따라서 다음은 **검증되지 않는다.**
- 직경 지수 `p_d`와 질량 지수 `p_m`: 탄이 하나뿐이라 변화하지 않는다. 이 지수들은 [S1]의 니켈강 값을 *사전 가정(prior)* 으로 쓰는 것이다.
- 다른 구경·질량의 AP에 대한 `K`의 이식성: [S1]이 스스로 "탄의 품질 유사성을 가정한다"고 말한다.

그래서 v1의 정직한 표현은 "**M318A1과 비슷한 비피모 AP**에 대해, 4.0–6.0 in RHA에서 수직 충돌의 한계속도를 약 ±2 % 수준으로 재현한다"이다. 다른 구경은 `EXTRAPOLATED`/`OUT_OF_MODEL`이고, 그 폭은 **다른 구경의 데이터가 생길 때까지 넓히지 않는다.**

**K의 소유자 문제.** de Marre의 K는 장갑 쪽 계수로 정의되지만 실제로는 *(장갑, 탄 품질)* 의 쌍이다. v1은 K를 `ResistancePreset`(재질×solver)에 두되 `projectile_family`와 `calibration_projectile`을 함께 기록해 불일치를 막는다. 더 많은 탄 데이터가 생기면 탄 쪽 품질 계수의 도입을 별도 리뷰한다(11절 결정 1).

## 6. 보정 출처 구조 — `CalibrationProvenance`

프리셋의 `provenance` 필드(구조체. 필수 필드는 없다):

```
CalibrationProvenance {
  sourceId, title, authors[], year, documentId, doi,
  testStandard, armorStandard, plateClass, plateThickness, armorHardness,
  ballisticLimitDefinition,
  projectile { designation, family, diameter, mass, massSource },
  velocityRange, diameterThicknessRange, obliquityRange,
  fitMethod, sampleCount, effectiveIndependentSamples, rmse,
  notes
}
```

JSON 예 — *구조만 보여 주는 예이며 production 값이 아니다*(`null` = 아직 확인되지 않음):

```json
{
  "source_id": "mil-dtl-12560k-tbl-a-vii",
  "title": "Armor Plate, Steel, Wrought, Homogeneous (for use in combat-vehicles and for ammunition testing)",
  "document_id": "MIL-DTL-12560K (MR), 07 Dec 2013, Appendix A, Table A-VII",
  "test_standard": "USATECOM TOP 2-2-710 via MIL-DTL-12560K A.4.1",
  "armor_standard": "MIL-DTL-12560K",
  "plate_class": "1&3",
  "plate_thickness_in": [3.94, 6.06],
  "armor_hardness_hb": [250, 300],
  "ballistic_limit_definition": "PROTECTION_LIMIT",
  "projectile": { "designation": "90 mm M318A1 AP", "family": "AP_UNCAPPED", "diameter_mm": 90, "mass_kg": null, "mass_source": null },
  "obliquity_deg": 0,
  "fit_method": "log-space least squares, exponents fixed (DeMarreProfile)",
  "sample_count": 212,
  "effective_independent_samples": null,
  "rmse": null,
  "notes": "Acceptance MINIMA (requirements), not measured V50; piecewise-linear table."
}
```

## 7. 물리 결과와 게임플레이 정책의 분리

`PhysicalBallisticsResult`(위 solver의 결과)와 `GameplayPolicy`는 서로 다른 층이다. **de Marre 계수 `K`나 지수를 밸런스용으로 바꾸지 않는다.** 밸런스가 필요하면 별도 데이터(`balance_modifier`)가 *정책 층*에서 결과를 조정하며, 그 값은 JSON에서 명시적이고 solver는 읽지 않는다. 이유: 탄도·장갑·spall·모듈 피해·의료가 연결돼 있어 관통 계수가 밸런스 손잡이가 되면 전체 보정이 깨진다.

## 8. RHA 기준 — `RHA_REFERENCE`

`RHA_REFERENCE` = MIL-DTL-12560 호환 균질 압연(wrought) 장갑. 경도 하나를 고정하지 않는다. 판의 규격 요구는 두께로 다르다 [S3 Table II]. 보정 데이터마다 **재질 규격, 판 클래스, 두께, 경도, 시험 조건**을 provenance에 남긴다. `ArmorMaterial`의 `resistance` 매핑은 `solver → preset`이며, 한계 정의·클래스·경도 범위가 다른 프리셋은 서로 다른 프리셋 id를 가진다(예: `dncity:rha_ap_class13_protection_limit`).

## 9. 경험적인 것과 보존법칙의 구분

| 항목 | 성격 |
|---|---|
| 에너지 비생성(traversal이 강제), SI 단위, 기하(`perforationPoint`, 경로) | 법칙/기하 |
| `NO_PLUG_ENERGY_UPPER_BOUND`의 에너지 균형 | **명시적 가정**(플러그 없음, 질량·변형 불변). 에너지 보존을 만족하지만 *잔류속도를 과대평가* |
| `V_dM`의 거듭제곱 형태와 지수 0.75/0.70/0.50 | **경험적**, 1937년 해군 교범의 니켈강 값. RHA에서 검증되지 않았다(직경·질량은 검증 불가) |
| `K` | **경험적 보정 계수**, 한계 정의·판 클래스·탄 가족에 종속 |
| 유효 구간·`extrapolation_margin` | 보정 데이터에서 유도된 **경험적 한계** |
| 잔류속도 계수(플러그, `a`, `p`) | **보정 불가**(데이터 없음) → v1 제외 |
| 경사 배수 | **v1 제외.** 1937 `sec⁴`는 D2에서 반증됨(30→45° 비 1.30 vs 2.25) |
| 도탄·파쇄·부분관통·마모 | **미모델** |

## 10. 테스트 계획 (승인 후)

- **역사적 재현**: 지수/상수가 [S1]의 예제 3개를 재현(Problem I 연철 1772 ft/s, II 니켈강 1772 ft/s, IV `K = 1.23`). 단위 변환 검증(SI). 이 테스트는 *고정 값이 아니라 교범의 인쇄 값*을 기준으로 한다.
- **속성**: `v_bl`은 `T`, `d`에 단조 증가, `m`에 단조 감소. `v_i ≤ v_bl`이면 STOPPED. 임의 입력(퍼징)에서 에너지가 생기지 않고 `deposited ≥ 0`.
- **구간**: 클램프가 없음을 증명하는 테스트(구간 밖의 값을 구간 안 값으로 계산하면 실패). 세 판정의 경계. 가족 불일치, `LONG_ROD`, `penetrator != null`, 알 수 없는 프리셋 → `OUT_OF_MODEL`.
- **결정성**: 같은 `ImpactContext`는 같은 결과.
- **보정 회귀**: D1 CSV에 대해 보정 후 재현 오차가 provenance의 `rmse` 안(자기 일관성). 이것은 모델 검증이 아님을 테스트 이름에 명시한다.
- **traversal 통합**: 기존 합성 테스트는 그대로 통과하고, 실제 solver로 두 층 시나리오 한 개.

## 11. 결정이 필요한 사항

1. **보정 범위와 K의 소유권.** [D1]은 단일 탄이라 직경·질량 지수는 검증되지 않고 K의 이식성도 보장되지 않는다. v1을 "M318A1 계열 한정 + 나머지 `EXTRAPOLATED/OUT_OF_MODEL`"로 좁게 시작해도 되는가? 다른 구경 데이터(예: 소구경 AP, 별도 가족)를 추가 조사해 교차 검증할 것인가?
2. **`OUT_OF_MODEL`의 런타임 처리**: (a) 예외 + 서버가 해석 불가 샷으로 처리, (b) `fallback_solver` 위임, 중 무엇인가? (v1 프리셋에서는 모든 경사 충돌이 여기에 해당한다. 서버 샷 처리부가 아직 없다.)
3. **PERFORATED의 의미**: PROTECTION_LIMIT([D1])은 파편도 완전관통으로 센다. 이 모델의 `PERFORATED`를 "탄 자체의 관통"으로 쓸지 "판 뒤 효과 발생"으로 쓸지 정해야 한다.
4. **`NO_PLUG_ENERGY_UPPER_BOUND` 기준선 승인**. 잔류속도 과대평가 편향을 인정하고 시작해도 되는가, 아니면 R–I/L–J 원문을 확인한 뒤로 잔류속도 구현을 미룰 것인가?
5. **자료 제공 요청**: Recht–Ipson(1963) PDF, Lambert & Jonas(1976, BRL-R-1852) PDF. M318A1의 질량·치수·탄두 구성을 밝히는 신뢰 출처(예: TM 9-1300-203 원문).
6. 비피모 AP의 **경사 데이터**와 **잔류속도 데이터**를 별도 단계에서 조사하는 것을 승인하는가(현재 둘 다 없다).

## 12. 결정 기록 (리뷰 후)

1. M318A1 한정 baseline(`M318A1_LIKE_UNCAPPED_AP`). 다른 구경/질량은 `EXTRAPOLATED`/`OUT_OF_MODEL`. 프리셋은 `allowed_projectiles` 목록으로 대상을 명시한다.
2. `OUT_OF_MODEL`은 예외가 아니라 `Unresolved` 값. fallback은 resolver 소유(4절).
3. `PERFORATED` = 장갑 뒤에 탄 잔류 상태가 존재. `PROTECTION_LIMIT`은 시험 관측의 정의이며 provenance `systematic_uncertainty_notes`에 체계적 불확실성으로 기록한다.
4. 잔류속도 전략 이름은 `NO_PLUG_ENERGY_UPPER_BOUND`(잔류 운동에너지 상한). 결과 진단 `RESIDUAL_MODEL_UNCALIBRATED`.
5. M318A1 질량은 독립 두 출처가 같은 조립체 정의를 줄 때까지 production K 금지. 코드가 `TWO_INDEPENDENT_SOURCES` 아니면 프리셋 로드를 거부한다.
6. 경사 데이터셋(A)과 잔류속도 데이터셋(B)은 별도 조사. M82 APC 데이터는 M318A1 보정에 섞지 않는다.

## 13. 리뷰 2차 반영 (구현 기준)

- **축 정리.** 데이터가 바꾸는 것은 판 두께뿐이다. `EXTRAPOLATED`는 두께 축에만 존재한다. 직경·질량은 *보정값*이며 수치 오차 허용(`numerical_tolerance`)만 허용하고 다르면 `OUT_OF_MODEL`. `valid_ranges`에는 `thickness_m`만 올 수 있다(로더 강제).
- **0° 보정과 수치 허용 분리.** 보정된 입사각은 0° 하나뿐이다. `normal_incidence_tolerance_deg`는 기하의 부동소수점 오차를 위한 값이며 검증된 물리 범위가 아니다(코드의 상한 0.05°). 이 값 안에서도 `EXTRAPOLATED`는 없다.
- **provenance 불변식.** `ballistic_limit_definition`이 `MINIMUM_PERFORATION`이 아니면 `limit_outcome_mismatch`(관측 한계가 DNCity `PERFORATED`와 어떻게 다른지)가 비어 있지 않아야 로드된다. PROTECTION_LIMIT은 증인판 파편도 완전관통으로 센다.
- **임계 분기.** 한계속도 분기가 먼저 STOPPED/PERFORATED를 정한다. 잔류속도 식은 `v_i > v_bl`에서만 호출되며 `max(0, …)`로 오류를 가리지 않는다(아니면 예외).
- **TODO (임시 구조).** `allowed_projectiles`(탄 ID 목록)는 임시다. 장기적으로 `penetratorConstruction` 분류(`FULL_CALIBER_MONOBLOC`, `CAPPED_FULL_CALIBER`, `COMPOSITE_RIGID`, `SABOT`, `LONG_ROD`, `SHAPED_CHARGE`, …)로 교체해야 한다. `M318A1` 같은 특정 ID를 알아야 solver가 동작하는 구조는 최종 아키텍처가 아니다.
- `10.93 kg`와 `calibrationCoefficient = 1.1631`은 **fixture 전용**이며 M318A1 물성이 아니다.
