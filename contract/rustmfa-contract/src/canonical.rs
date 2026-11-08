use crate::ContractError;

pub struct ChallengeMessage<'a> {
    pub challenge_id: &'a str,
    pub nonce: &'a str,
    pub login_id: &'a str,
    pub device_id: &'a str,
    pub expires_at: &'a str,
    pub audience: &'a str,
}

fn check(name: &'static str, v: &str) -> Result<(), ContractError> {
    if v.is_empty() || v.contains('\n') {
        Err(ContractError::InvalidField(name))
    } else {
        Ok(())
    }
}

/// Accepts exactly `YYYY-MM-DDTHH:MM:SSZ` (20 ASCII bytes): real calendar date,
/// no fraction, no leap second, so Rust and Kotlin agree on every input.
fn valid_expiry(s: &str) -> bool {
    if s.len() != 20 || !s.is_ascii() {
        return false;
    }
    let b = s.as_bytes();
    let separators = b[4] == b'-' && b[7] == b'-' && b[10] == b'T' && b[13] == b':' && b[16] == b':' && b[19] == b'Z';
    let digits = [0, 1, 2, 3, 5, 6, 8, 9, 11, 12, 14, 15, 17, 18].iter().all(|&i| b[i].is_ascii_digit());
    if !separators || !digits {
        return false;
    }
    let num = |r: std::ops::Range<usize>| s[r].parse::<u32>().unwrap();
    let (year, month, day) = (num(0..4), num(5..7), num(8..10));
    let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    let days = match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if leap => 29,
        2 => 28,
        _ => return false,
    };
    (1..=days).contains(&day) && num(11..13) < 24 && num(14..16) < 60 && num(17..19) < 60
}

pub fn canonical_message(m: &ChallengeMessage) -> Result<String, ContractError> {
    check("challenge_id", m.challenge_id)?;
    check("nonce", m.nonce)?;
    check("login_id", m.login_id)?;
    check("device_id", m.device_id)?;
    check("audience", m.audience)?;
    if !valid_expiry(m.expires_at) {
        return Err(ContractError::InvalidExpiresAt);
    }
    Ok(["v1", m.challenge_id, m.nonce, m.login_id, m.device_id, m.expires_at, m.audience].join("\n"))
}
