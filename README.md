# AlphaWallet - The mobile wallet engine for the web3 world

[![Build Status](https://api.travis-ci.com/AlphaWallet/alpha-wallet-android.svg?branch=master)](https://api.travis-ci.com/AlphaWallet/alpha-wallet-android.svg?branch=master) 
[![License](https://img.shields.io/badge/license-GPL3-green.svg?style=flat)](https://github.com/fastlane/fastlane/blob/master/LICENSE)

[<img src=dmz/src/main/resources/static/images/googleplay.png height="88">](https://play.google.com/store/apps/details?id=io.stormbird.wallet&hl=en_US)

## Getting Started

1. [Download](https://developer.android.com/studio/) Android Studio.
1. Clone this repository
1. Run `./gradlew build` to install tools and dependencies.

## Contributing

The best way to submit feedback and report bugs is to open a GitHub issue.
Please be sure to include your operating system, device, version number, and
steps to reproduce reported bugs.

## GPL Acknowledgement

The codebase for this app is originally forked from the Trust ethereum wallet and has had many major modifications. Their Android repo can be seen here: https://github.com/TrustWallet/trust-wallet-android-source

## Using the signature checker

API to validate signed tokenscript XML file.

### Endpoint:

`http://stormbird.duckdns.org:8081/checkSig`
  
Use with Multipart file type eg:

`curl -X GET http://stormbird.duckdns.org:8081/checkSig -H "cache-control: no-cache" -H "content-type: multipart/form-data;" -F "file=@xml/signed.xml"`
  
## Result:

on signature not valid:

`{"result":"fail"}`

on signature valid:

`{
    "result": "pass",
    "subject": "<X500 Subject description>",
    "keyName": "<Name of subject>",
    "keyType": "<Crypto Key spec>",
    "issuer": "<X500 Issuer description>"
}`

eg.

`{
  "result":"pass",
  "subject":"CN=example.cn,OU=PositiveSSL,OU=Domain Control Validated",
  "keyName":"Jarvis",
  "keyType":"SHA256withECDSA",
  "issuer":"CN=COMODO ECC Domain Validation Secure Server CA,O=COMODO CA Limited,L=Dubbo,ST=NSW,C=AU"
}`

