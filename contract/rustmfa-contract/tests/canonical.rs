use rustmfa_contract::{canonical_message, ChallengeMessage, ContractError};

fn base() -> ChallengeMessage<'static> {
    ChallengeMessage {
        challenge_id: "ch_8a31",
        nonce: "n0nce",
        login_id: "login_7f21",
        device_id: "dev_7f21",
        expires_at: "2026-10-28T15:03:30Z",
        audience: "rostdav",
    }
}

#[test]
fn builds_exact_message() {
    assert_eq!(
        canonical_message(&base()).unwrap(),
        "v1\nch_8a31\nn0nce\nlogin_7f21\ndev_7f21\n2026-10-28T15:03:30Z\nrostdav"
    );
}

#[test]
fn rejects_newline_in_any_field() {
    let bad = "x\ny";
    let cases: Vec<(&str, ChallengeMessage)> = vec![
        ("challenge_id", ChallengeMessage { challenge_id: bad, ..base() }),
        ("nonce", ChallengeMessage { nonce: bad, ..base() }),
        ("login_id", ChallengeMessage { login_id: bad, ..base() }),
        ("device_id", ChallengeMessage { device_id: bad, ..base() }),
        ("audience", ChallengeMessage { audience: bad, ..base() }),
    ];
    for (name, m) in cases {
        assert_eq!(canonical_message(&m), Err(ContractError::InvalidField(name)));
    }
}

#[test]
fn rejects_empty_field() {
    let m = ChallengeMessage { nonce: "", ..base() };
    assert_eq!(canonical_message(&m), Err(ContractError::InvalidField("nonce")));
}

#[test]
fn rejects_offset_expiry() {
    let m = ChallengeMessage { expires_at: "2026-10-28T16:03:30+01:00", ..base() };
    assert_eq!(canonical_message(&m), Err(ContractError::InvalidExpiresAt));
}

#[test]
fn rejects_expiry_without_z() {
    let m = ChallengeMessage { expires_at: "2026-10-28T15:03:30", ..base() };
    assert_eq!(canonical_message(&m), Err(ContractError::InvalidExpiresAt));
}

fn with_expiry(e: &str) -> Result<String, ContractError> {
    canonical_message(&ChallengeMessage { expires_at: e, ..base() })
}

#[test]
fn rejects_impossible_calendar_dates() {
    for e in ["2026-02-31T00:00:00Z", "2026-04-31T00:00:00Z", "2025-02-29T00:00:00Z",
        "2026-13-01T00:00:00Z", "2026-00-10T00:00:00Z", "2026-10-00T00:00:00Z"] {
        assert_eq!(with_expiry(e), Err(ContractError::InvalidExpiresAt), "{e}");
    }
}

#[test]
fn accepts_leap_day_in_leap_year() {
    assert!(with_expiry("2028-02-29T00:00:00Z").is_ok());
}

#[test]
fn rejects_hour_24_and_leap_second() {
    for e in ["2026-10-28T24:00:00Z", "2026-10-28T15:60:00Z", "2026-10-28T15:03:60Z"] {
        assert_eq!(with_expiry(e), Err(ContractError::InvalidExpiresAt), "{e}");
    }
}

#[test]
fn rejects_fractional_seconds() {
    for e in ["2026-10-28T15:03:30.123Z", "2026-10-28T15:03:30.Z"] {
        assert_eq!(with_expiry(e), Err(ContractError::InvalidExpiresAt), "{e}");
    }
}

#[test]
fn rejects_lowercase_and_non_ascii_without_panic() {
    for e in ["2026-10-28t15:03:30Z", "2026-10-28T15:03:30z", "é026-10-28T15:03:30Z", "2026-10-28T15:03:3éZ"] {
        assert_eq!(with_expiry(e), Err(ContractError::InvalidExpiresAt), "{e}");
    }
}
