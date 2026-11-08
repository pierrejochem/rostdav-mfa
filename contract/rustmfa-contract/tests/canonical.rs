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
