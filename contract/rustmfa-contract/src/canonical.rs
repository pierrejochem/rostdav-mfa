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

/// Accepts `YYYY-MM-DDTHH:MM:SS(.digits)?Z` only.
fn valid_expiry(s: &str) -> bool {
    let b = s.as_bytes();
    if b.len() < 20 || b[b.len() - 1] != b'Z' {
        return false;
    }
    let digits = |r: std::ops::Range<usize>| b[r].iter().all(u8::is_ascii_digit);
    let num = |r: std::ops::Range<usize>| s[r].parse::<u32>().unwrap_or(u32::MAX);
    let shape = digits(0..4)
        && b[4] == b'-'
        && digits(5..7)
        && b[7] == b'-'
        && digits(8..10)
        && b[10] == b'T'
        && digits(11..13)
        && b[13] == b':'
        && digits(14..16)
        && b[16] == b':'
        && digits(17..19);
    if !shape {
        return false;
    }
    let frac_ok = match &s[19..s.len() - 1] {
        "" => true,
        f => f.len() > 1 && f.starts_with('.') && f[1..].bytes().all(|c| c.is_ascii_digit()),
    };
    frac_ok
        && (1..=12).contains(&num(5..7))
        && (1..=31).contains(&num(8..10))
        && num(11..13) < 24
        && num(14..16) < 60
        && num(17..19) <= 60
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
