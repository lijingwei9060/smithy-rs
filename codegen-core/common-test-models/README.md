## change for server sdk

1. remove smithy.rules#endpointRuleSet
2. remove smithy.rules#endpointTests 
3. change pattern
4. add errors: "target": "smithy.framework#ValidationException"

## add model to build.gradle.kts


## build

/home/casoul/lijingwei9060/smithy-rs/gradlew --project-dir /home/casoul/lijingwei9060/smithy-rs -P modules='sts' :codegen-server-test:clean
/home/casoul/lijingwei9060/smithy-rs/gradlew --project-dir /home/casoul/lijingwei9060/smithy-rs -P modules='sts' :codegen-server-test:assemble