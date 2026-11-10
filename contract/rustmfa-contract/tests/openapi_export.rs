//! The crate carries the JSON mirror of the OpenAPI file, so a dependent crate can check its
//! responses against the contract without a sibling checkout.

#[test]
fn the_exported_json_is_the_mirror_file_and_is_openapi_3_1() {
    let file = concat!(env!("CARGO_MANIFEST_DIR"), "/../openapi/rustmfa-v1.json");
    assert_eq!(
        rustmfa_contract::OPENAPI_JSON,
        std::fs::read_to_string(file).expect("mirror file")
    );
    let v: serde_json::Value = serde_json::from_str(rustmfa_contract::OPENAPI_JSON).expect("json");
    assert!(v["openapi"].as_str().is_some_and(|s| s.starts_with("3.1")));
}
