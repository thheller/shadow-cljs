# Notes on creating a self-signed ssl cert

https://certsimple.com/blog/localhost-ssl-fix

until you get a .p12 file, then

```
keytool -importkeystore -destkeystore keystore.jks -srcstoretype PKCS12 -srckeystore test-cert.p12
```

this is ok but requires a password which I don't want to have in the shadow-cljs config.




