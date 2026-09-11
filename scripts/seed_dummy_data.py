#!/usr/bin/env python3
"""Seed a local backend with synthetic (SIMULATED) dummy data so every screen has something to show.

The script drives the public and receiver APIs exactly like a receiver would: it signs in, registers
two insole pairs, sends heartbeats, creates measurement sessions, streams deterministic synthetic gait
frames, completes the sessions and waits for the rule-v1.4.0 analysis. Each scenario is tuned so a
different pattern code, quality level or session status shows up in the UI.

Every session starts with a 2.0 s quiet-standing segment (both feet loaded, still) before the gait
cycles, matching the live-page protocol and the backend's QUIET_STANDING reference detection. Most
scenarios also carry a synthetic shank IMU (schemaVersion 1.1: accelMg/gyroDps10 per frame, rotated by
a fixed per-foot mounting rotation so the backend's automatic axis alignment is exercised) so the
'움직임 분석' card has data; balanced_50hz and cancelled stay schemaVersion 1.0 without IMU so the
"no IMU" state stays visible. --no-imu sends every session without IMU.

After the API flow the session timestamps are optionally spread over the past three weeks with a direct
MySQL update (mysql.exe + the .env credentials) so the history page looks like real usage. Nothing here
has clinical meaning; every session is created with sourceType SIMULATED unless --source-type says
otherwise.

    python scripts/seed_dummy_data.py              # sign in with SEED_ADMIN_* from .env and seed
    python scripts/seed_dummy_data.py --dry-run    # only print the predicted pattern/movement outcome per scenario
    python scripts/seed_dummy_data.py --live 60    # additionally stream a paced 60 s session for the live page
    python scripts/seed_dummy_data.py --reset      # delete the account's earlier dummy data first (MySQL)
    python scripts/seed_dummy_data.py --no-imu     # schemaVersion 1.0 everywhere (movementSummary stays null)

Credentials (SEED_ADMIN_EMAIL/PASSWORD, RECEIVER_API_KEY, DB_*) are read from the repository .env and
are never printed.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import shutil
import statistics
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from http_tools import HttpResult, describe_error, join_url, request_json

SCRIPT_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIR.parent
ADC_MAX = 4095
LAYOUT_VERSION = "layout-s01s08-v1"
# layout-s01s08-v1 (V6 seed): index -> (region, medialLateral, x, y). Used only for the dry-run predictor.
LAYOUT_POINTS = [
    ("HEEL", "MEDIAL", 0.40, 0.88),
    ("HEEL", "LATERAL", 0.62, 0.88),
    ("MIDFOOT", "MEDIAL", 0.36, 0.62),
    ("MIDFOOT", "LATERAL", 0.66, 0.62),
    ("FOREFOOT", "MEDIAL", 0.32, 0.36),
    ("FOREFOOT", "CENTER", 0.50, 0.34),
    ("FOREFOOT", "LATERAL", 0.70, 0.38),
    ("TOE", "MEDIAL", 0.36, 0.12),
]
REGION_OF = [point[0] for point in LAYOUT_POINTS]
# rule-v1.2.0 functional-test thresholds (application.yml); the predictor mirrors them.
CONTACT_THRESHOLD = 3.75 * 8
ASYMMETRY_PCT = 10.0
MEDIAL_RATIO = 0.60
LATERAL_RATIO = 0.60
FOREFOOT_RATIO = 0.60
REARFOOT_RATIO = 0.55
HALLUX_SHARE_PCT = 5.0
PARTIAL_RATE = 0.20
REPEATED_RATE = 0.60
MIN_WINDOWS = 4
PATTERN_CODES = [
    "MEDIAL_LOAD_TENDENCY",
    "LATERAL_LOAD_TENDENCY",
    "LEFT_RIGHT_ASYMMETRY",
    "LOW_HALLUX_SIGNAL",
    "FOREFOOT_LOAD_TENDENCY",
    "REARFOOT_LOAD_TENDENCY",
]

# Sensor weight sets (S01..S08). Medial = S01,S03,S05,S08; lateral = S02,S04,S07; S06 is CENTER.
WEIGHTS = {
    "balanced": [0.78, 0.80, 0.30, 0.42, 0.62, 0.70, 0.62, 0.40],
    "rearfoot": [1.00, 0.95, 0.18, 0.20, 0.25, 0.25, 0.22, 0.18],
    "medial": [0.95, 0.45, 0.55, 0.20, 0.85, 0.55, 0.30, 0.60],
    "lateral_low_hallux": [0.45, 0.95, 0.20, 0.60, 0.35, 0.60, 0.90, 0.10],
    "forefoot": [0.35, 0.35, 0.20, 0.22, 0.85, 1.00, 0.95, 0.55],
}
GAIT_CYCLE_S = 1.10
STANCE_ENVELOPE = {  # region -> (center, width) of the roll-over bump inside the stance phase
    "HEEL": (0.22, 0.30),
    "MIDFOOT": (0.48, 0.30),
    "FOREFOOT": (0.70, 0.30),
    "TOE": (0.86, 0.22),
}

# Quiet standing at the start of every session: the live page counts down about 2 s after 시작 and the
# backend (rule-v1.4.0) looks for the first still interval with both feet loaded to build the IMU
# reference posture. Both feet carry balanced weights at half load with tiny jitter.
STANDING_S = 2.0
STANDING_MS = int(STANDING_S * 1000)
STANDING_LOAD = 0.5
# Per-frame receivedAt (schemaVersion 1.1) is derived from the device timeline plus a per-foot receiver
# latency so the backend's left/right window pairing (receiver time order) matches the device order.
RECEIVER_LATENCY_MS = {"LEFT": 2, "RIGHT": 5}
INT16_MIN, INT16_MAX = -32768, 32767
# Fixed per-foot mounting rotation of the body-mounted board (degrees about x, then about z). The
# canonical shank vectors are rotated with it before being sent so the backend has to recover the axes
# itself (per-session automatic alignment); the dry-run predictor un-rotates with the same matrix.
MOUNT_ROTATION_DEG = {"LEFT": (35.0, 20.0), "RIGHT": (-28.0, -15.0)}


@dataclass(frozen=True)
class ImuProfile:
    """Synthetic shank IMU of one session (functional-test values, no clinical meaning).

    Canonical shank body frame: x forward, y left, z up; at rest the sensor reads +1 g along +z.
    The frontal tilt follows the rule-v1.4.0 formula sign (atan2(g·lateral, g·up); + = 바깥쪽,
    lateral = +y for the LEFT foot and -y for the RIGHT foot), the forward swing is a negative
    rotation about the left axis (the backend's sign rule) and stance carries a slower positive one.
    """

    tilt_left_deg: float = 0.0  # frontal tilt held during mid-stance, LEFT shank
    tilt_right_deg: float = 0.0  # frontal tilt held during mid-stance, RIGHT shank
    swing_peak_dps: float = 300.0  # peak |gyro·ml| of the forward swing
    stance_dps: float = 40.0  # plateau of the slow positive stance rotation (range ~15-25 deg)
    transverse_dps: float = 15.0  # small vertical-axis rotation inside stance


@dataclass(frozen=True)
class GaitProfile:
    """Deterministic synthetic gait for one session."""

    styles: tuple[tuple[str, float], ...] = (("balanced", 1.0),)  # (weight set, probability)
    left_stance: float = 0.62  # fraction of the gait cycle in contact
    right_stance: float = 0.62
    amplitude: int = 2600
    drop_right: bool = False
    gap_every: int = 0  # drop every n-th frame (sequence gaps)
    shuffle_batches: bool = False  # deliver frames of a batch out of order
    stuck_sensor: tuple[str, int] | None = None  # (footSide, sensor index) pinned at ADC_MAX


@dataclass(frozen=True)
class Scenario:
    key: str
    memo: str
    sample_rate_hz: int
    duration_s: float
    gait: GaitProfile
    outcome: str = "complete"  # complete | cancel | created | failed
    days_ago: int = 0
    time_of_day: str = "09:00"
    expected: str = ""
    imu: ImuProfile | None = ImuProfile()  # None -> schemaVersion 1.0 batch without IMU


SCENARIOS: list[Scenario] = [
    Scenario("balanced_50hz", "첫 측정 · 거실에서 천천히 걷기", 50, 40, GaitProfile(),
             days_ago=20, time_of_day="08:10", expected="패턴 없음 · 품질 좋음 · 50Hz · IMU 없음", imu=None),
    Scenario("rearfoot", "뒤꿈치로 딛는 느낌이 들어 다시 측정", 100, 35,
             GaitProfile(styles=(("rearfoot", 1.0),)),
             days_ago=18, time_of_day="19:40", expected="REARFOOT_LOAD_TENDENCY 반복 관찰",
             imu=ImuProfile(swing_peak_dps=260.0, stance_dps=34.0)),
    Scenario("cancelled", "인솔이 미끄러져 측정 중단", 100, 3, GaitProfile(), outcome="cancel",
             days_ago=16, time_of_day="07:55", expected="취소됨", imu=None),
    Scenario("medial", "공원 산책 후 측정", 100, 45,
             GaitProfile(styles=(("medial", 1.0),)),
             days_ago=14, time_of_day="10:20", expected="MEDIAL_LOAD_TENDENCY 반복 관찰",
             imu=ImuProfile(tilt_left_deg=-5.0, tilt_right_deg=-5.0)),
    Scenario("asymmetry", "왼발이 무겁게 느껴짐", 100, 40,
             GaitProfile(left_stance=0.70, right_stance=0.56),
             days_ago=12, time_of_day="18:05", expected="LEFT_RIGHT_ASYMMETRY 반복 관찰",
             imu=ImuProfile(tilt_left_deg=1.5, tilt_right_deg=-2.0)),
    Scenario("forefoot", "빠르게 걷기", 100, 30,
             GaitProfile(styles=(("forefoot", 1.0),)),
             days_ago=10, time_of_day="12:30", expected="FOREFOOT_LOAD_TENDENCY 반복 관찰",
             imu=ImuProfile(tilt_left_deg=2.0, tilt_right_deg=2.0, swing_peak_dps=340.0, stance_dps=44.0)),
    Scenario("poor_quality", "오른발 인솔 연결 불량", 100, 25,
             GaitProfile(drop_right=True, gap_every=4, shuffle_batches=True),
             days_ago=9, time_of_day="21:15", expected="품질 낮음 · 재측정 안내 · 오른발 데이터 없음"),
    Scenario("lateral_low_hallux", "바깥쪽으로 걷는 느낌", 50, 45,
             GaitProfile(styles=(("lateral_low_hallux", 1.0),)),
             days_ago=7, time_of_day="09:45", expected="LATERAL_LOAD_TENDENCY + LOW_HALLUX_SIGNAL",
             imu=ImuProfile(tilt_left_deg=6.0, tilt_right_deg=6.0, transverse_dps=18.0)),
    Scenario("failed", "분석 실패 예시", 100, 10, GaitProfile(), outcome="failed",
             days_ago=5, time_of_day="16:20", expected="실패 (DB 상태 변경)"),
    Scenario("sensor_stuck", "오른발 센서 하나가 이상한 것 같음", 100, 30,
             GaitProfile(stuck_sensor=("RIGHT", 3)),
             days_ago=4, time_of_day="08:35", expected="SENSOR_STUCK_OR_SATURATED · 품질 확인 필요"),
    Scenario("mixed_partial", "계단 오르내리기", 100, 40,
             GaitProfile(styles=(("medial", 0.35), ("rearfoot", 0.25), ("balanced", 0.40))),
             days_ago=3, time_of_day="17:50", expected="MEDIAL/REARFOOT 일부 관찰",
             imu=ImuProfile(tilt_left_deg=-2.0, tilt_right_deg=-1.5)),
    Scenario("balanced_100hz", "오늘 아침 산책", 100, 60, GaitProfile(),
             days_ago=1, time_of_day="07:30", expected="패턴 없음 · 품질 좋음 · 100Hz"),
    Scenario("created", "오후 측정 예정", 100, 0, GaitProfile(), outcome="created",
             days_ago=0, expected="준비됨"),
]


# ----------------------------------------------------------------------------------------------------
# .env and arguments
# ----------------------------------------------------------------------------------------------------


def load_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
            value = value[1:-1]
        values[key.strip()] = value
    return values


def positive_float(value: str) -> float:
    parsed = float(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return parsed


def parse_args() -> argparse.Namespace:
    env = load_env_file(REPOSITORY_ROOT / ".env")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=os.getenv("SMART_INSOLE_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--email", default=os.getenv("SMART_INSOLE_SEED_EMAIL", env.get("SEED_ADMIN_EMAIL")))
    parser.add_argument("--password", default=os.getenv("SMART_INSOLE_SEED_PASSWORD", env.get("SEED_ADMIN_PASSWORD")))
    parser.add_argument("--name", default="데모 사용자", help="display name used only with --signup")
    parser.add_argument("--signup", action="store_true", help="create the account first if it does not exist")
    parser.add_argument("--receiver-key", default=os.getenv("SMART_INSOLE_RECEIVER_KEY", env.get("RECEIVER_API_KEY")))
    parser.add_argument("--run-id", default="demo", help="suffix of the device serial numbers (re-runs reuse them)")
    parser.add_argument("--source-type", choices=["SIMULATED", "DEVICE"], default="SIMULATED")
    parser.add_argument("--scenario", action="append", help="only run the named scenario(s)")
    parser.add_argument("--list", action="store_true", help="print the scenarios and exit")
    parser.add_argument("--dry-run", action="store_true", help="predict the analysis outcome locally, no HTTP")
    parser.add_argument("--no-imu", action="store_true",
                        help="send every session as schemaVersion 1.0 without IMU vectors")
    parser.add_argument("--seed", type=int, default=20260910)
    parser.add_argument("--live", type=positive_float, metavar="SECONDS",
                        help="after seeding, stream one real-time paced session for this long")
    parser.add_argument("--live-only", action="store_true", help="skip the scenarios, only run --live")
    parser.add_argument("--live-keep", action="store_true",
                        help="leave the live session MEASURING instead of completing it")
    parser.add_argument("--no-shift-dates", action="store_true",
                        help="keep the real timestamps instead of spreading sessions over the past weeks")
    parser.add_argument("--reset", action="store_true",
                        help="delete this account's devices, sessions and results before seeding (MySQL)")
    parser.add_argument("--mysql-exe", default=os.getenv("SMART_INSOLE_MYSQL_EXE"),
                        help="path to mysql.exe; auto-detected when omitted")
    parser.add_argument("--result-timeout-seconds", type=positive_float, default=90.0)
    parser.add_argument("--request-timeout-seconds", type=positive_float, default=30.0)
    args = parser.parse_args()
    args.env = env
    return args


# ----------------------------------------------------------------------------------------------------
# Synthetic gait generation
# ----------------------------------------------------------------------------------------------------


def bump(u: float, center: float, width: float) -> float:
    half = width / 2.0
    return math.exp(-((u - center) / half) ** 2)


def smoothstep(t: float) -> float:
    t = max(0.0, min(1.0, t))
    return t * t * (3.0 - 2.0 * t)


def plateau(u: float, rise: tuple[float, float] = (0.10, 0.28), fall: tuple[float, float] = (0.78, 0.96)) -> float:
    """Smooth 0..1 window inside the stance phase; fully 1 over the mid-stance (30-60 %) frames."""

    return smoothstep((u - rise[0]) / (rise[1] - rise[0])) * (1.0 - smoothstep((u - fall[0]) / (fall[1] - fall[0])))


def stance_values(u: float, weights: list[float], amplitude: int, rng: random.Random) -> list[int]:
    """Raw ADC values at stance progress u in [0,1): shared body-weight load plus a heel-to-toe roll."""

    base = math.sin(math.pi * u)
    values: list[int] = []
    for index, weight in enumerate(weights):
        center, width = STANCE_ENVELOPE[REGION_OF[index]]
        envelope = 0.55 * base + 0.45 * bump(u, center, width)
        value = round(24 + amplitude * weight * envelope + rng.randint(-12, 12))
        values.append(max(0, min(ADC_MAX, value)))
    return values


def standing_values(amplitude: int, rng: random.Random) -> list[int]:
    """Raw ADC values while standing still: balanced weights at half load with tiny jitter."""

    return [max(0, min(ADC_MAX, round(24 + amplitude * STANDING_LOAD * weight + rng.randint(-6, 6))))
            for weight in WEIGHTS["balanced"]]


def swing_values(rng: random.Random) -> list[int]:
    return [max(0, 24 + rng.randint(-10, 10)) for _ in range(8)]


def choose_style(styles: tuple[tuple[str, float], ...], rng: random.Random) -> str:
    total = sum(probability for _, probability in styles)
    draw = rng.random() * total
    for name, probability in styles:
        draw -= probability
        if draw <= 0:
            return name
    return styles[-1][0]


# --- shank IMU (rule-v1.4.0 functional-test input) ---------------------------------------------------

Vector = tuple[float, float, float]
Matrix = list[list[float]]


def rotation_matrix(x_deg: float, z_deg: float) -> Matrix:
    """Rz(z_deg) · Rx(x_deg): the fixed mounting rotation from the shank body frame to the sensor frame."""

    cx, sx = math.cos(math.radians(x_deg)), math.sin(math.radians(x_deg))
    cz, sz = math.cos(math.radians(z_deg)), math.sin(math.radians(z_deg))
    rx = [[1.0, 0.0, 0.0], [0.0, cx, -sx], [0.0, sx, cx]]
    rz = [[cz, -sz, 0.0], [sz, cz, 0.0], [0.0, 0.0, 1.0]]
    return [[sum(rz[i][k] * rx[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def transpose(m: Matrix) -> Matrix:
    return [[m[j][i] for j in range(3)] for i in range(3)]


def rotate(m: Matrix, v: Vector) -> Vector:
    return (sum(m[0][k] * v[k] for k in range(3)), sum(m[1][k] * v[k] for k in range(3)),
            sum(m[2][k] * v[k] for k in range(3)))


def dot(a: Vector, b: Vector) -> float:
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def unit(a: Vector) -> Vector:
    norm = math.sqrt(dot(a, a))
    return (a[0] / norm, a[1] / norm, a[2] / norm) if norm else a


def mean_vector(vectors: list[Vector]) -> Vector:
    count = len(vectors)
    return (sum(v[0] for v in vectors) / count, sum(v[1] for v in vectors) / count,
            sum(v[2] for v in vectors) / count)


def shank_motion(side: str, state: str, u: float, imu: ImuProfile, rng: random.Random) -> tuple[Vector, Vector]:
    """Body-frame accel (mg) and gyro (deg/s) of the shank at progress u in [0,1) of a gait state.

    Standing: still, gravity only. Stance: the shank rolls forward over the planted foot (slow positive
    rotation about the left axis with a short negative dip at loading), holds the scenario's frontal
    tilt during mid-stance and adds a small vertical-axis rotation. Swing: one smooth forward-swing
    bump (negative about the left axis) with a forward acceleration ripple.
    """

    lateral_sign = 1.0 if side == "LEFT" else -1.0
    tilt = imu.tilt_left_deg if side == "LEFT" else imu.tilt_right_deg
    pitch = roll = 0.0
    motion: list[float] = [0.0, 0.0, 0.0]
    if state == "standing":
        gyro: list[float] = [0.0, 0.0, 0.0]
    elif state == "stance":
        envelope = plateau(u)
        roll = tilt * envelope
        pitch = -6.0 + 18.0 * u
        gyro = [5.0 * math.sin(2 * math.pi * u),
                imu.stance_dps * envelope - 40.0 * bump(u, 0.06, 0.12),
                imu.transverse_dps * math.sin(2 * math.pi * u) * envelope]
        motion = [-150.0 * bump(u, 0.03, 0.08) + 180.0 * bump(u, 0.93, 0.10), 0.0, 350.0 * bump(u, 0.03, 0.08)]
    else:  # swing
        pitch = 12.0 - 20.0 * u
        gyro = [6.0 * math.sin(2 * math.pi * u),
                -imu.swing_peak_dps * math.sin(math.pi * u) ** 2,
                12.0 * math.sin(2 * math.pi * u)]
        motion = [250.0 * math.sin(2 * math.pi * u), 0.0, -100.0 * math.sin(math.pi * u)]
    theta, phi = math.radians(pitch), math.radians(roll)
    # World "up" expressed in the body frame (pitch theta about y, roll phi about x): the specific force
    # a resting accelerometer reports is +1 g along this direction.
    gravity = (-math.sin(theta), lateral_sign * math.sin(phi) * math.cos(theta), math.cos(phi) * math.cos(theta))
    accel = tuple(1000.0 * g + m + rng.uniform(-4.0, 4.0) for g, m in zip(gravity, motion))
    return accel, tuple(g + rng.uniform(-0.5, 0.5) for g in gyro)  # type: ignore[return-value]


def int16(value: float) -> int:
    return max(INT16_MIN, min(INT16_MAX, round(value)))


def sensor_vectors(mount: Matrix, accel: Vector, gyro: Vector) -> tuple[tuple[int, int, int], tuple[int, int, int]]:
    """Rotate body-frame vectors into the mounted sensor frame and quantise to int16 mg / 0.1 deg/s."""

    a = rotate(mount, accel)
    g = rotate(mount, gyro)
    return (int16(a[0]), int16(a[1]), int16(a[2])), (int16(g[0] * 10), int16(g[1] * 10), int16(g[2] * 10))


@dataclass
class Frame:
    foot_side: str
    sequence: int
    device_time_ms: int
    sensor_values: list[int]
    accel_mg: tuple[int, int, int] | None = None
    gyro_dps10: tuple[int, int, int] | None = None


def generate_frames(profile: GaitProfile, sample_rate_hz: int, duration_s: float, seed: int,
                    *, imu: ImuProfile | None = None, start_sequence: int = 0,
                    start_device_time_ms: int = 0) -> dict[str, list[Frame]]:
    """Frames per foot in device order: STANDING_S of quiet standing, then gait cycles.

    Sequence gaps, stuck sensors and the synthetic shank IMU are applied here. The IMU jitter uses its
    own generator so the pressure values are identical with and without IMU.
    """

    rng = random.Random(seed)
    imu_rng = random.Random(f"{seed}:imu")
    period_ms = round(1000 / sample_rate_hz)
    total_frames = int(duration_s * sample_rate_hz)
    cycle_ms = GAIT_CYCLE_S * 1000.0
    frames: dict[str, list[Frame]] = {"LEFT": [], "RIGHT": []}
    step_weights: dict[str, dict[int, list[float]]] = {"LEFT": {}, "RIGHT": {}}
    mounts = {side: rotation_matrix(*MOUNT_ROTATION_DEG[side]) for side in frames}
    for side, stance, offset_ms in (("LEFT", profile.left_stance, 0.0), ("RIGHT", profile.right_stance, cycle_ms / 2)):
        if side == "RIGHT" and profile.drop_right:
            continue
        for tick in range(total_frames):
            device_time = start_device_time_ms + tick * period_ms
            sequence = start_sequence + tick
            if profile.gap_every and (tick + 1) % profile.gap_every == 0:
                continue  # never sent: creates a sequence gap
            if device_time < STANDING_MS:
                state, progress = "standing", device_time / STANDING_MS
                values = standing_values(profile.amplitude, rng)
            else:
                gait_ms = device_time - STANDING_MS
                local_ms = (gait_ms + offset_ms) % cycle_ms
                phase = local_ms / cycle_ms
                if phase < stance:
                    cycle_index = int((gait_ms + offset_ms) // cycle_ms)
                    weights = step_weights[side].get(cycle_index)
                    if weights is None:
                        # Style per (foot, gait cycle) is derived from the seed so paced live chunks agree.
                        style_rng = random.Random(f"{seed}:{side}:{cycle_index}")
                        weights = WEIGHTS[choose_style(profile.styles, style_rng)]
                        step_weights[side][cycle_index] = weights
                    state, progress = "stance", phase / stance
                    values = stance_values(progress, weights, profile.amplitude, rng)
                else:
                    state, progress = "swing", (phase - stance) / (1.0 - stance)
                    values = swing_values(rng)
            if profile.stuck_sensor and profile.stuck_sensor[0] == side:
                values[profile.stuck_sensor[1]] = ADC_MAX
            frame = Frame(side, sequence, device_time, values)
            if imu is not None:
                accel, gyro = shank_motion(side, state, progress, imu, imu_rng)
                frame.accel_mg, frame.gyro_dps10 = sensor_vectors(mounts[side], accel, gyro)
            frames[side].append(frame)
    return frames


# ----------------------------------------------------------------------------------------------------
# Local predictor (mirror of RuleBasedAnalyzer for the dry-run; the backend is the source of truth)
# ----------------------------------------------------------------------------------------------------


def normalized(frames: list[Frame]) -> list[list[float]]:
    raw = [[value * 100.0 / ADC_MAX for value in frame.sensor_values] for frame in frames]
    smoothed: list[list[float]] = []
    for index in range(len(raw)):
        lo, hi = max(0, index - 1), min(len(raw) - 1, index + 1)
        smoothed.append([sum(raw[sample][sensor] for sample in range(lo, hi + 1)) / (hi - lo + 1)
                         for sensor in range(8)])
    return smoothed


def contact_windows(frames: list[Frame], values: list[list[float]], sample_rate_hz: int) -> list[tuple[int, int, float]]:
    period = 1000.0 / sample_rate_hz
    windows: list[tuple[int, int, float]] = []
    start: int | None = None
    previous_time: int | None = None
    for index, frame in enumerate(frames):
        if previous_time is not None and frame.device_time_ms - previous_time > period * 3 and start is not None:
            windows.append(window(frames, start, index - 1, period))
            start = None
        contact = sum(values[index]) >= CONTACT_THRESHOLD
        if contact and start is None:
            start = index
        if not contact and start is not None:
            windows.append(window(frames, start, index - 1, period))
            start = None
        previous_time = frame.device_time_ms
    if start is not None:
        windows.append(window(frames, start, len(frames) - 1, period))
    return windows


def window(frames: list[Frame], start: int, end: int, period: float) -> tuple[int, int, float]:
    duration = frames[end].device_time_ms - frames[start].device_time_ms + period
    return start, end, max(period, duration)


def window_metrics(values: list[list[float]]) -> dict[str, float]:
    medial = lateral = heel = forefoot = hallux = total = 0.0
    for frame in values:
        for index, value in enumerate(frame):
            region, side, _, _ = LAYOUT_POINTS[index]
            total += value
            if side == "MEDIAL":
                medial += value
            if side == "LATERAL":
                lateral += value
            if region == "HEEL":
                heel += value
            if region in {"FOREFOOT", "TOE"}:
                forefoot += value
            if region == "TOE" and side == "MEDIAL":
                hallux += value
    side_total = medial + lateral
    return {
        "medial": medial / side_total if side_total else 0.0,
        "lateral": lateral / side_total if side_total else 0.0,
        "heel": heel / total if total else 0.0,
        "forefoot": forefoot / total if total else 0.0,
        "hallux_pct": hallux / total * 100.0 if total else 0.0,
    }


def level(rate: float, windows: int) -> str:
    if windows < MIN_WINDOWS:
        return "NOT_OBSERVED"
    if rate >= REPEATED_RATE:
        return "REPEATEDLY_OBSERVED"
    if rate >= PARTIAL_RATE:
        return "PARTIALLY_OBSERVED"
    return "NOT_OBSERVED"


def predict(frames: dict[str, list[Frame]], sample_rate_hz: int) -> list[str]:
    per_side: dict[str, tuple[list[tuple[int, int, float]], list[list[float]]]] = {}
    metrics: list[dict[str, float]] = []
    for side in ("LEFT", "RIGHT"):
        values = normalized(frames[side])
        windows = contact_windows(frames[side], values, sample_rate_hz)
        per_side[side] = (windows, values)
        for start, end, _ in windows:
            metrics.append(window_metrics(values[start:end + 1]))
    pairs = min(len(per_side["LEFT"][0]), len(per_side["RIGHT"][0]))
    asymmetric = 0
    for index in range(pairs):
        left = per_side["LEFT"][0][index][2]
        right = per_side["RIGHT"][0][index][2]
        mean = (left + right) / 2.0
        if mean > 0 and abs(left - right) / mean * 100.0 >= ASYMMETRY_PCT:
            asymmetric += 1
    count = len(metrics)
    observed = {
        "MEDIAL_LOAD_TENDENCY": (sum(1 for m in metrics if m["medial"] >= MEDIAL_RATIO), count),
        "LATERAL_LOAD_TENDENCY": (sum(1 for m in metrics if m["lateral"] >= LATERAL_RATIO), count),
        "LEFT_RIGHT_ASYMMETRY": (asymmetric, pairs),
        "LOW_HALLUX_SIGNAL": (sum(1 for m in metrics if m["hallux_pct"] < HALLUX_SHARE_PCT), count),
        "FOREFOOT_LOAD_TENDENCY": (sum(1 for m in metrics if m["forefoot"] >= FOREFOOT_RATIO), count),
        "REARFOOT_LOAD_TENDENCY": (sum(1 for m in metrics if m["heel"] >= REARFOOT_RATIO), count),
    }
    lines = [f"windows L={len(per_side['LEFT'][0])} R={len(per_side['RIGHT'][0])} pairs={pairs}"]
    for code in PATTERN_CODES:
        hits, windows = observed[code]
        rate = hits / windows if windows else 0.0
        lines.append(f"{code:<24} {level(rate, windows):<20} {rate * 100:5.1f}% ({hits}/{windows})")
    return lines


def integrated_range(frames: list[Frame], gyro: list[Vector], start: int, end: int, axis: Vector) -> float:
    """Range (max - min, deg) of the cumulative trapezoid integral of gyro·axis inside a window."""

    angle = lowest = highest = 0.0
    for index in range(start + 1, end + 1):
        dt = (frames[index].device_time_ms - frames[index - 1].device_time_ms) / 1000.0
        angle += 0.5 * (dot(gyro[index - 1], axis) + dot(gyro[index], axis)) * dt
        lowest, highest = min(lowest, angle), max(highest, angle)
    return highest - lowest


def movement_predict(frames: list[Frame], windows: list[tuple[int, int, float]], side: str) -> dict[str, Any] | None:
    """Mirror of the rule-v1.4.0 shank metrics with the known mounting rotation instead of the backend's
    PCA alignment (keys follow MovementFootSummary). Reference = the quiet-standing frames."""

    if not frames or any(frame.accel_mg is None for frame in frames):
        return None
    inverse = transpose(rotation_matrix(*MOUNT_ROTATION_DEG[side]))
    accel = [rotate(inverse, tuple(float(v) for v in frame.accel_mg)) for frame in frames]  # type: ignore[arg-type]
    gyro = [rotate(inverse, tuple(v / 10.0 for v in frame.gyro_dps10)) for frame in frames]  # type: ignore[arg-type]
    standing = [a for frame, a in zip(frames, accel) if frame.device_time_ms < STANDING_MS]
    up = unit(mean_vector(standing)) if standing else (0.0, 0.0, 1.0)
    ml_left = (0.0, 1.0, 0.0)
    ml_left = unit(tuple(m - dot(ml_left, up) * u for m, u in zip(ml_left, up)))  # type: ignore[arg-type]
    lateral = ml_left if side == "LEFT" else (-ml_left[0], -ml_left[1], -ml_left[2])
    tilts: list[float] = []
    sagittal: list[float] = []
    transverse: list[float] = []
    peaks: list[float] = []
    for index, (start, end, _) in enumerate(windows):
        count = end - start + 1
        mid_start = start + int(count * 0.3)
        mid_end = start + max(int(count * 0.3) + 1, int(count * 0.6))
        g = unit(mean_vector(accel[mid_start:mid_end]))
        tilts.append(math.degrees(math.atan2(dot(g, lateral), dot(g, up))))
        sagittal.append(integrated_range(frames, gyro, start, end, ml_left))
        transverse.append(integrated_range(frames, gyro, start, end, up))
        if index + 1 < len(windows) and windows[index + 1][0] > end + 1:
            peaks.append(max(abs(dot(gyro[i], ml_left)) for i in range(end + 1, windows[index + 1][0])))
    return {
        "frontalTiltDeg": statistics.fmean(tilts) if tilts else None,
        "sagittalRangeDeg": statistics.median(sagittal) if sagittal else None,
        "transverseRangeDeg": statistics.median(transverse) if transverse else None,
        "swingPeakAngularVelocityDps": statistics.median(peaks) if peaks else None,
        "windowCount": len(windows),
    }


def format_foot(foot: dict[str, Any] | None) -> str:
    """One MovementFootSummary (API result or dry-run prediction) as a compact string."""

    if foot is None:
        return "null"

    def number(key: str, spec: str, suffix: str = "") -> str:
        value = foot.get(key)
        return "null" if value is None else f"{value:{spec}}{suffix}"

    return (f"tilt={number('frontalTiltDeg', '+.1f', '°')} sag={number('sagittalRangeDeg', '.1f', '°')} "
            f"trans={number('transverseRangeDeg', '.1f', '°')} swing={number('swingPeakAngularVelocityDps', '.0f', 'dps')} "
            f"windows={foot.get('windowCount')}")


def movement_lines(frames: dict[str, list[Frame]], imu: ImuProfile | None, sample_rate_hz: int) -> list[str]:
    if imu is None:
        return ["movement: schemaVersion 1.0 (IMU 없음) -> movementSummary null 예상"]
    both = bool(frames["LEFT"]) and bool(frames["RIGHT"])
    reference = "QUIET_STANDING" if both else "FIRST_STANCE (한쪽 발 없음)"
    lines = [f"movement: schemaVersion 1.1, standing {STANDING_S:.1f}s, reference {reference} 예상"]
    for side in ("LEFT", "RIGHT"):
        windows = contact_windows(frames[side], normalized(frames[side]), sample_rate_hz)
        lines.append(f"  {side:<5} {format_foot(movement_predict(frames[side], windows, side))}")
    return lines


def describe_movement(result: dict[str, Any]) -> str:
    """movementSummary of an analysis result for the console summary (null states stay visible)."""

    summary = result.get("movementSummary")
    if summary is None:
        return "movement=null"
    coverage = summary.get("imuCoverage")
    parts = [f"movement: imu={'null' if coverage is None else f'{coverage:.2f}'} ref={summary.get('referenceMethod')}"]
    for side in ("left", "right"):
        parts.append(f"{side[0].upper()}[{format_foot(summary.get(side))}]")
    return " ".join(parts)


# ----------------------------------------------------------------------------------------------------
# HTTP helpers
# ----------------------------------------------------------------------------------------------------


class Api:
    def __init__(self, base_url: str, timeout_seconds: float):
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.token: str | None = None
        self.receiver_key: str | None = None

    def call(self, method: str, path: str, *, payload: Any = None, receiver: bool = False,
             attempts: int = 3) -> HttpResult:
        headers: dict[str, str] = {}
        if receiver:
            if not self.receiver_key:
                raise RuntimeError("receiver key missing (RECEIVER_API_KEY in .env or --receiver-key)")
            headers["X-Receiver-Key"] = self.receiver_key
        elif self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return request_json(method, join_url(self.base_url, path), payload=payload, headers=headers,
                            timeout_seconds=self.timeout_seconds, max_attempts=attempts)

    def checked(self, method: str, path: str, expected: set[int], label: str, *, payload: Any = None,
                receiver: bool = False) -> Any:
        result = self.call(method, path, payload=payload, receiver=receiver)
        if result.status not in expected:
            raise RuntimeError(f"{label}: {describe_error(result)} (expected {sorted(expected)})")
        return result.body


def iso_utc(moment: datetime, *, micro: bool = False) -> str:
    text = moment.astimezone(timezone.utc).isoformat(timespec="microseconds" if micro else "milliseconds")
    return text.replace("+00:00", "Z")


def utc_now() -> str:
    return iso_utc(datetime.now(timezone.utc))


def public_endpoint(url: str) -> str:
    parsed = urlsplit(url)
    host = parsed.hostname or "<invalid>"
    return f"{parsed.scheme}://{host}:{parsed.port}" if parsed.port else f"{parsed.scheme}://{host}"


def sign_in(api: Api, args: argparse.Namespace) -> dict[str, Any]:
    if not args.email or not args.password:
        raise RuntimeError("email/password missing: set SEED_ADMIN_EMAIL/SEED_ADMIN_PASSWORD in .env or pass --email/--password")
    if args.signup:
        signup = api.call("POST", "/api/v1/auth/signup",
                          payload={"email": args.email, "password": args.password, "name": args.name})
        if signup.status not in {201, 409}:
            raise RuntimeError(f"signup: {describe_error(signup)}")
    body = api.checked("POST", "/api/v1/auth/signin", {200}, "signin",
                       payload={"email": args.email, "password": args.password})
    api.token = body["accessToken"]
    user = body.get("user") or {}
    print(f"[OK] signed in as {user.get('email', args.email)} (name={user.get('name')!r})")
    return user


def register_device(api: Api, *, serial: str, name: str, side: str, firmware: str) -> dict[str, Any]:
    payload = {"serialNumber": serial, "displayName": name, "footSide": side, "sensorCount": 8,
               "sensorLayoutVersion": LAYOUT_VERSION, "firmwareVersion": firmware, "adcMax": ADC_MAX}
    result = api.call("POST", "/api/v1/devices", payload=payload)
    if result.status == 201:
        print(f"[OK] registered {side} device {serial}")
        return result.body
    if result.status != 409:
        raise RuntimeError(f"register device {serial}: {describe_error(result)}")
    devices = api.checked("GET", "/api/v1/devices", {200}, "list devices")
    for device in devices:
        if device.get("serialNumber") == serial:
            print(f"[OK] reusing existing {side} device {serial}")
            return device
    raise RuntimeError(f"device {serial} exists but is owned by another account")


def heartbeat(api: Api, device_id: str, *, connected: bool, battery_pct: float | None, battery_mv: int | None,
              firmware: str, rssi: int) -> None:
    api.checked("POST", f"/internal/v1/devices/{device_id}/heartbeat", {200, 202, 204}, "heartbeat", receiver=True,
                payload={"receiverId": "DUMMY-RECEIVER", "observedAt": utc_now(), "connected": connected,
                         "batteryPercent": battery_pct, "batteryMv": battery_mv, "firmwareVersion": firmware,
                         "rssi": rssi})


def receiver_status(api: Api, session_id: str, state: str, pending: int) -> None:
    api.checked("POST", f"/internal/v1/measurement-sessions/{session_id}/receiver-status", {200, 202, 204},
                "receiver-status", receiver=True,
                payload={"receiverId": "DUMMY-RECEIVER", "state": state, "pendingBatchCount": pending,
                         "observedAt": utc_now()})


@dataclass
class Stream:
    """Batch envelope state of one session upload: schemaVersion and the synthetic receiver clock.

    schemaVersion 1.1 batches carry batchId plus per-frame receivedAt/protocolVersion/dataMode/
    calibrated/imuAvailable and the IMU vectors; 1.0 batches carry the five mandatory frame fields only.
    """

    schema_version: str
    received_origin: datetime  # receiver wall clock of deviceTimeMs 0
    batch_count: int = 0

    @classmethod
    def for_upload(cls, with_imu: bool, duration_s: float) -> "Stream":
        # Scenario sessions are uploaded faster than real time, so the receiver clock starts in the past
        # as if the frames had been received during the last duration_s seconds.
        return cls("1.1" if with_imu else "1.0", datetime.now(timezone.utc) - timedelta(seconds=duration_s))

    @classmethod
    def for_live(cls, with_imu: bool) -> "Stream":
        return cls("1.1" if with_imu else "1.0", datetime.now(timezone.utc))

    def envelope(self, session_id: str) -> dict[str, Any]:
        envelope: dict[str, Any] = {"schemaVersion": self.schema_version, "receiverId": "DUMMY-RECEIVER",
                                    "sentAt": utc_now()}
        if self.schema_version == "1.1":
            envelope["batchId"] = f"{session_id}-{self.batch_count:06d}"
        self.batch_count += 1
        return envelope

    def frame_payload(self, frame: Frame, device_ids: dict[str, str]) -> dict[str, Any]:
        payload: dict[str, Any] = {"deviceId": device_ids[frame.foot_side], "footSide": frame.foot_side,
                                   "sequence": frame.sequence, "deviceTimeMs": frame.device_time_ms,
                                   "sensorValues": frame.sensor_values}
        if self.schema_version != "1.1":
            return payload
        received = self.received_origin + timedelta(milliseconds=frame.device_time_ms + RECEIVER_LATENCY_MS[frame.foot_side])
        payload.update({"protocolVersion": 1, "receivedAt": iso_utc(received, micro=True), "dataMode": "RAW",
                        "calibrated": False, "imuAvailable": frame.accel_mg is not None})
        if frame.accel_mg is not None and frame.gyro_dps10 is not None:
            payload["accelMg"] = list(frame.accel_mg)
            payload["gyroDps10"] = list(frame.gyro_dps10)
        return payload


def batches(frames: dict[str, list[Frame]], sample_rate_hz: int, window_ms: int) -> list[list[Frame]]:
    """Group both feet into device-time windows of window_ms (at most 200 frames per batch)."""

    merged = sorted(frames["LEFT"] + frames["RIGHT"], key=lambda f: (f.device_time_ms, f.foot_side))
    if not merged:
        return []
    result: list[list[Frame]] = []
    origin = merged[0].device_time_ms
    current: list[Frame] = []
    current_slot = 0
    for frame in merged:
        slot = (frame.device_time_ms - origin) // window_ms
        if current and (slot != current_slot or len(current) >= 200):
            result.append(current)
            current = []
        current_slot = slot
        current.append(frame)
    if current:
        result.append(current)
    return result


def send_batch(api: Api, session_id: str, batch: list[Frame], device_ids: dict[str, str], stream: Stream, *,
               shuffle: bool, rng: random.Random) -> tuple[int, int, int]:
    frames = list(batch)
    if shuffle:
        rng.shuffle(frames)
    payload = stream.envelope(session_id)
    payload["frames"] = [stream.frame_payload(frame, device_ids) for frame in frames]
    body = api.checked("POST", f"/internal/v1/measurement-sessions/{session_id}/frame-batches", {200},
                       "frame batch", receiver=True, payload=payload)
    counts = (body.get("acceptedCount", 0), body.get("duplicateCount", 0), body.get("rejectedCount", 0))
    if counts[2]:
        raise RuntimeError(f"frame batch rejected {counts[2]} frame(s): {json.dumps(body)[:300]}")
    return counts


def wait_for_result(api: Api, session_id: str, timeout_seconds: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        result = api.call("GET", f"/api/v1/measurement-sessions/{session_id}/result", attempts=1)
        if result.status == 200:
            return result.body
        if result.status != 202:
            raise RuntimeError(f"result: {describe_error(result)}")
        time.sleep(0.5)
    raise RuntimeError(f"analysis did not finish within {timeout_seconds:.0f}s")


# ----------------------------------------------------------------------------------------------------
# MySQL helpers (timestamp spreading, reset, FAILED example)
# ----------------------------------------------------------------------------------------------------


@dataclass
class MySql:
    exe: str
    host: str
    port: str
    database: str
    user: str
    password: str

    @classmethod
    def from_env(cls, env: dict[str, str], override_exe: str | None) -> "MySql | None":
        exe = override_exe or shutil.which("mysql")
        if not exe:
            for candidate in (
                Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "MySQL" / "MySQL Server 8.0" / "bin" / "mysql.exe",
                Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "MySQL" / "MySQL Server 8.4" / "bin" / "mysql.exe",
            ):
                if candidate.exists():
                    exe = str(candidate)
                    break
        user = env.get("DB_USERNAME") or env.get("MYSQL_USER")
        password = env.get("DB_PASSWORD") or env.get("MYSQL_PASSWORD")
        database = env.get("MYSQL_DATABASE")
        host, port = "127.0.0.1", env.get("MYSQL_PORT", "3306")
        url = env.get("DB_URL", "")
        if url.startswith("jdbc:"):
            parsed = urlsplit(url[len("jdbc:"):])
            host = parsed.hostname or host
            port = str(parsed.port or port)
            database = database or parsed.path.strip("/")
        if not (exe and user and password and database):
            return None
        return cls(exe, host, port, database, user, password)

    def run(self, sql: str) -> str:
        env = {**os.environ, "MYSQL_PWD": self.password}
        completed = subprocess.run(
            [self.exe, "-h", self.host, "-P", self.port, "-u", self.user, "-D", self.database,
             "--default-character-set=utf8mb4", "-N", "--batch"],
            input=sql, env=env, capture_output=True, text=True, encoding="utf-8",
        )
        if completed.returncode != 0:
            raise RuntimeError(f"mysql failed: {completed.stderr.strip()[:400]}")
        return completed.stdout


def sql_ts(moment: datetime) -> str:
    return "'" + moment.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S.%f") + "'"


def shift_sql(session_id: str, created: datetime, duration_s: float, has_start: bool, has_end: bool) -> str:
    started = created + timedelta(seconds=25)
    ended = started + timedelta(seconds=duration_s + 4)
    analysed = ended + timedelta(seconds=3)
    frame_time = f"DATE_ADD({sql_ts(started)}, INTERVAL device_time_ms * 1000 MICROSECOND)"
    statements = [
        "UPDATE measurement_sessions SET created_at={c}, updated_at={u}{s}{e} WHERE id='{id}';".format(
            c=sql_ts(created), u=sql_ts(ended if has_end else (started if has_start else created)),
            s=f", started_at={sql_ts(started)}" if has_start else "",
            e=f", ended_at={sql_ts(ended)}" if has_end else "", id=session_id),
        f"UPDATE measurement_quality_stats SET updated_at={sql_ts(ended)} WHERE session_id='{session_id}';",
        f"UPDATE analysis_jobs SET created_at={sql_ts(ended)}, started_at={sql_ts(ended)}, completed_at={sql_ts(analysed)} "
        f"WHERE session_id='{session_id}' AND completed_at IS NOT NULL;",
        f"UPDATE analysis_results SET created_at={sql_ts(analysed)} WHERE session_id='{session_id}';",
        # The 1.1 per-frame receiver time moves with the batch receipt time so both stay on the shifted day.
        f"UPDATE pressure_frames SET received_at={frame_time}, "
        f"receiver_received_at=IF(receiver_received_at IS NULL, NULL, {frame_time}) "
        f"WHERE session_id='{session_id}';",
    ]
    return "\n".join(statements)


RESET_SQL = """
SET @uid = (SELECT id FROM users WHERE email = {email});
DELETE rr FROM result_recommendations rr JOIN analysis_results ar ON ar.id = rr.analysis_result_id
    JOIN measurement_sessions s ON s.id = ar.session_id WHERE s.user_id = @uid;
DELETE ap FROM analysis_patterns ap JOIN analysis_results ar ON ar.id = ap.analysis_result_id
    JOIN measurement_sessions s ON s.id = ar.session_id WHERE s.user_id = @uid;
DELETE ar FROM analysis_results ar JOIN measurement_sessions s ON s.id = ar.session_id WHERE s.user_id = @uid;
DELETE aj FROM analysis_jobs aj JOIN measurement_sessions s ON s.id = aj.session_id WHERE s.user_id = @uid;
DELETE q FROM measurement_quality_stats q JOIN measurement_sessions s ON s.id = q.session_id WHERE s.user_id = @uid;
DELETE f FROM pressure_frames f JOIN measurement_sessions s ON s.id = f.session_id WHERE s.user_id = @uid;
DELETE FROM measurement_sessions WHERE user_id = @uid;
DELETE c FROM calibration_profiles c JOIN devices d ON d.id = c.device_id WHERE d.user_id = @uid;
DELETE FROM devices WHERE user_id = @uid;
SELECT ROW_COUNT();
"""


def sql_string(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


# ----------------------------------------------------------------------------------------------------
# Scenario runner
# ----------------------------------------------------------------------------------------------------


@dataclass
class SessionRecord:
    scenario: Scenario
    session_id: str
    status: str
    quality: int | None = None
    quality_level: str | None = None
    patterns: list[str] = field(default_factory=list)
    recommendations: list[str] = field(default_factory=list)
    flags: list[str] = field(default_factory=list)
    steps: int | None = None
    movement: str = "-"
    has_start: bool = False
    has_end: bool = False


def create_session(api: Api, device_ids: dict[str, str], scenario: Scenario, source_type: str) -> str:
    body = api.checked("POST", "/api/v1/measurement-sessions", {201}, "create session",
                       payload={"leftDeviceId": device_ids["LEFT"], "rightDeviceId": device_ids["RIGHT"],
                                "sampleRateHz": scenario.sample_rate_hz, "sourceType": source_type,
                                "memo": scenario.memo})
    return body["sessionId"]


def apply_result(record: SessionRecord, result: dict[str, Any]) -> None:
    record.status = "COMPLETED"
    quality = result.get("dataQuality") or {}
    record.quality = quality.get("score")
    record.quality_level = quality.get("level")
    record.flags = list(quality.get("flags") or [])
    record.patterns = [f"{p['code']}({p.get('observationLevel')})" for p in result.get("patterns") or []]
    record.recommendations = [r["code"] for r in result.get("recommendations") or []]
    record.steps = (result.get("gaitSummary") or {}).get("validStepCount")
    record.movement = describe_movement(result)


def run_scenario(api: Api, device_ids: dict[str, str], scenario: Scenario, args: argparse.Namespace,
                 index: int) -> SessionRecord:
    imu = None if args.no_imu else scenario.imu
    print(f"\n=== [{scenario.key}] {scenario.memo} ({scenario.sample_rate_hz}Hz, {scenario.duration_s:.0f}s, "
          f"{'IMU' if imu else 'no IMU'}) ===")
    session_id = create_session(api, device_ids, scenario, args.source_type)
    record = SessionRecord(scenario, session_id, "CREATED")
    print(f"[OK] session {session_id} created")
    if scenario.outcome == "created":
        return record
    api.checked("POST", f"/api/v1/measurement-sessions/{session_id}/start", {200}, "start session")
    record.status, record.has_start = "MEASURING", True
    rng = random.Random(args.seed + index)
    frames = generate_frames(scenario.gait, scenario.sample_rate_hz, scenario.duration_s, args.seed + index, imu=imu)
    stream = Stream.for_upload(imu is not None, scenario.duration_s)
    accepted = 0
    receiver_status(api, session_id, "STREAMING", 0)
    for batch in batches(frames, scenario.sample_rate_hz, 1000):
        counts = send_batch(api, session_id, batch, device_ids, stream, shuffle=scenario.gait.shuffle_batches, rng=rng)
        accepted += counts[0]
    total = len(frames["LEFT"]) + len(frames["RIGHT"])
    print(f"[OK] {accepted}/{total} frames accepted (schemaVersion {stream.schema_version}, "
          f"{STANDING_S:.1f}s standing + gait)")
    if scenario.outcome == "cancel":
        receiver_status(api, session_id, "UPLOAD_COMPLETE", 0)
        api.checked("POST", f"/api/v1/measurement-sessions/{session_id}/cancel", {200}, "cancel session")
        record.status, record.has_end = "CANCELLED", True
        print("[OK] session cancelled")
        return record
    receiver_status(api, session_id, "UPLOAD_COMPLETE", 0)
    api.checked("POST", f"/api/v1/measurement-sessions/{session_id}/complete", {200}, "complete session")
    record.has_end = True
    result = wait_for_result(api, session_id, args.result_timeout_seconds)
    apply_result(record, result)
    print(f"[OK] analysis: quality={record.quality} ({record.quality_level}) steps={record.steps}")
    print(f"     patterns={record.patterns or '없음'}")
    print(f"     recommendations={record.recommendations} flags={record.flags}")
    print(f"     {record.movement}")
    return record


def run_live(api: Api, device_ids: dict[str, str], args: argparse.Namespace) -> SessionRecord:
    scenario = Scenario("live", "실시간 화면 확인용 스트리밍", 100, args.live,
                        GaitProfile(styles=(("balanced", 0.7), ("medial", 0.3)), left_stance=0.64, right_stance=0.60),
                        imu=ImuProfile(tilt_left_deg=1.0, tilt_right_deg=0.5))
    imu = None if args.no_imu else scenario.imu
    print(f"\n=== [live] streaming {args.live:.0f}s at {scenario.sample_rate_hz}Hz "
          f"({STANDING_S:.1f}s standing first, {'IMU' if imu else 'no IMU'}; Ctrl+C stops early) ===")
    session_id = create_session(api, device_ids, scenario, args.source_type)
    api.checked("POST", f"/api/v1/measurement-sessions/{session_id}/start", {200}, "start session")
    record = SessionRecord(scenario, session_id, "MEASURING", has_start=True)
    print(f"[OK] live session {session_id}")
    print(f"     open http://localhost:5173/measurements/{session_id}/live")
    period_ms = round(1000 / scenario.sample_rate_hz)
    window_ms = 100
    per_window = window_ms // period_ms
    rng = random.Random(args.seed + 999)
    stream = Stream.for_live(imu is not None)
    started = time.monotonic()
    last_heartbeat = 0.0
    tick = 0
    accepted = 0
    try:
        while time.monotonic() - started < args.live:
            chunk = generate_frames(scenario.gait, scenario.sample_rate_hz, per_window * period_ms / 1000.0,
                                    args.seed + 999 + tick, imu=imu, start_sequence=tick,
                                    start_device_time_ms=tick * period_ms)
            counts = send_batch(api, session_id, chunk["LEFT"] + chunk["RIGHT"], device_ids, stream,
                                shuffle=False, rng=rng)
            accepted += counts[0]
            tick += per_window
            elapsed = time.monotonic() - started
            if elapsed - last_heartbeat >= 5.0:
                last_heartbeat = elapsed
                drain = max(0.0, 100.0 - elapsed * 0.4)
                heartbeat(api, device_ids["LEFT"], connected=True, battery_pct=round(min(87.0, drain), 1),
                          battery_mv=3960, firmware="0.2.0", rssi=-58)
                heartbeat(api, device_ids["RIGHT"], connected=True, battery_pct=round(min(79.0, drain), 1),
                          battery_mv=3890, firmware="0.2.0", rssi=-63)
                receiver_status(api, session_id, "STREAMING", 0)
                print(f"     {elapsed:5.1f}s streamed, {accepted} frames accepted")
            next_due = started + (tick / scenario.sample_rate_hz)
            time.sleep(max(0.0, next_due - time.monotonic()))
    except KeyboardInterrupt:
        print("\n[..] interrupted, closing the live session")
    if args.live_keep:
        print("[OK] live session left MEASURING (--live-keep)")
        return record
    receiver_status(api, session_id, "UPLOAD_COMPLETE", 0)
    api.checked("POST", f"/api/v1/measurement-sessions/{session_id}/complete", {200}, "complete session")
    record.has_end = True
    result = wait_for_result(api, session_id, args.result_timeout_seconds)
    apply_result(record, result)
    print(f"[OK] live session analysed: quality={record.quality} patterns={record.patterns or '없음'}")
    print(f"     {record.movement}")
    return record


def scenario_created_at(scenario: Scenario, now_local: datetime) -> datetime:
    hour, minute = (int(part) for part in scenario.time_of_day.split(":"))
    return (now_local - timedelta(days=scenario.days_ago)).replace(hour=hour, minute=minute, second=12, microsecond=0)


def main() -> int:
    for stream in (sys.stdout, sys.stderr):  # Korean memos on a cp949 console
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
    args = parse_args()
    scenarios = SCENARIOS
    if args.scenario:
        unknown = set(args.scenario) - {s.key for s in SCENARIOS}
        if unknown:
            print(f"[FAIL] unknown scenario(s): {sorted(unknown)}", file=sys.stderr)
            return 2
        scenarios = [s for s in SCENARIOS if s.key in set(args.scenario)]
    if args.list:
        for scenario in SCENARIOS:
            imu = "-" if args.no_imu or scenario.imu is None else "IMU"
            print(f"{scenario.key:<20} {scenario.sample_rate_hz:>3}Hz {scenario.duration_s:>4.0f}s  "
                  f"D-{scenario.days_ago:<2} {imu:<4} {scenario.expected}")
        return 0
    if args.dry_run:
        for index, scenario in enumerate(scenarios):
            if scenario.outcome in {"created", "cancel"}:
                continue
            imu = None if args.no_imu else scenario.imu
            frames = generate_frames(scenario.gait, scenario.sample_rate_hz, scenario.duration_s, args.seed + index,
                                     imu=imu)
            print(f"\n=== {scenario.key}: {scenario.expected}")
            print(f"frames L={len(frames['LEFT'])} R={len(frames['RIGHT'])} (first {STANDING_S:.1f}s standing)")
            for line in predict(frames, scenario.sample_rate_hz):
                print("  " + line)
            for line in movement_lines(frames, imu, scenario.sample_rate_hz):
                print("  " + line)
        return 0

    api = Api(args.base_url, args.request_timeout_seconds)
    api.receiver_key = args.receiver_key
    if not api.receiver_key:
        print("[FAIL] RECEIVER_API_KEY is missing (.env) and --receiver-key was not given", file=sys.stderr)
        return 2
    print(f"[SEED] baseUrl={public_endpoint(args.base_url)} sourceType={args.source_type} "
          f"imu={'off (--no-imu)' if args.no_imu else 'on (schemaVersion 1.1 where the scenario has IMU)'} "
          f"(synthetic data only)")
    mysql = MySql.from_env(args.env, args.mysql_exe)
    if mysql is None:
        print("[WARN] mysql.exe or DB_* credentials not found: dates will not be spread and --reset/failed example are skipped")

    try:
        user = sign_in(api, args)
        if args.reset:
            if mysql is None:
                print("[WARN] --reset ignored (no MySQL access)")
            else:
                mysql.run(RESET_SQL.format(email=sql_string(user.get("email") or args.email)))
                print("[OK] previous devices, sessions and results of this account deleted")

        run = args.run_id
        # The device list is ordered by registration time (newest first), so the spare pair goes in first
        # and the primary pair ends up on top of the dashboard.
        spare = {
            "LEFT": register_device(api, serial=f"SMART-INSOLE-L-{run}-spare", name="예비 왼발 인솔", side="LEFT", firmware="0.1.9"),
            "RIGHT": register_device(api, serial=f"SMART-INSOLE-R-{run}-spare", name="예비 오른발 인솔", side="RIGHT", firmware="0.1.9"),
        }
        primary = {
            "LEFT": register_device(api, serial=f"SMART-INSOLE-L-{run}", name="왼발 인솔", side="LEFT", firmware="0.2.0"),
            "RIGHT": register_device(api, serial=f"SMART-INSOLE-R-{run}", name="오른발 인솔", side="RIGHT", firmware="0.2.0"),
        }
        device_ids = {side: device["deviceId"] for side, device in primary.items()}
        heartbeat(api, device_ids["LEFT"], connected=True, battery_pct=87.0, battery_mv=3960, firmware="0.2.0", rssi=-58)
        heartbeat(api, device_ids["RIGHT"], connected=True, battery_pct=79.0, battery_mv=3890, firmware="0.2.0", rssi=-63)
        heartbeat(api, spare["LEFT"]["deviceId"], connected=False, battery_pct=12.0, battery_mv=3510, firmware="0.1.9", rssi=-84)
        heartbeat(api, spare["RIGHT"]["deviceId"], connected=False, battery_pct=None, battery_mv=None, firmware="0.1.9", rssi=-90)
        print("[OK] heartbeats sent (primary pair connected, spare pair disconnected)")

        records: list[SessionRecord] = []
        if not args.live_only:
            for index, scenario in enumerate(scenarios):
                records.append(run_scenario(api, device_ids, scenario, args, index))
        live_record = run_live(api, device_ids, args) if args.live else None

        if mysql is not None and records:
            # The backend writes TIMESTAMP literals as UTC wall-clock time through its default (SYSTEM)
            # session time zone, so the same convention is used here: UTC literals, no SET time_zone.
            statements: list[str] = []
            now_local = datetime.now().astimezone()
            for record in records:
                if record.scenario.outcome == "failed" and record.status == "COMPLETED":
                    statements.append(
                        f"UPDATE measurement_sessions SET status='FAILED' WHERE id='{record.session_id}';")
                    statements.append(
                        f"UPDATE analysis_jobs SET status='FAILED', error_code='ANALYSIS_FAILED', "
                        f"error_message='dummy failure for the UI' WHERE session_id='{record.session_id}';")
                    record.status = "FAILED"
                if not args.no_shift_dates and record.scenario.outcome != "created":
                    created = scenario_created_at(record.scenario, now_local)
                    statements.append(shift_sql(record.session_id, created, record.scenario.duration_s,
                                                record.has_start, record.has_end))
            if statements:
                mysql.run("\n".join(statements))
                print("\n[OK] session timestamps spread over the past weeks; FAILED example applied")

        print("\n================ SUMMARY ================")
        for record in records + ([live_record] if live_record else []):
            print(f"{record.scenario.key:<20} {record.status:<10} q={record.quality!s:<5} "
                  f"steps={record.steps!s:<4} {', '.join(record.patterns) or '-'}  "
                  f"rec={','.join(record.recommendations) or '-'}")
            print(f"{'':<20} {record.movement}")
            print(f"{'':<20} http://localhost:5173/measurements/{record.session_id}/"
                  f"{'live' if record.status in {'CREATED', 'MEASURING'} else 'result'}")
        print("\n로그인: 저장소 .env의 SEED_ADMIN_EMAIL / SEED_ADMIN_PASSWORD (화면 http://localhost:5173/login)")
        return 0
    except KeyboardInterrupt:
        print("\n[FAIL] interrupted", file=sys.stderr)
        return 130
    except (RuntimeError, OSError, KeyError, ValueError) as error:
        print(f"\n[FAIL] {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
