# Google OAuth Ktor Client plugin

This plugin is useful if you are making requests to Google APIs on behalf of multiple users using OAuth access and 
refresh tokens.

## What is difference with the default Bearer auth plugin?

There is a [Ktor Auth Plugin](https://github.com/ktorio/ktor/tree/main/ktor-client/ktor-client-plugins/ktor-client-auth) 
available. It can definitely be used for authentication with Google API, but it has several drawbacks:

- One Ktor Client can authenticate only one user. If you are making many requests on behalf of different users you will
  need to create many clients, which is not cheap. Our plugin accepts `Uid` attribute, which can be used to fetch
  different token for each user.
- There is no support for rejected refresh tokens. If user retracts permissions, default plugin will be stuck in 
  infinite loop.
- The default plugin is very generic, so you will have to implement token refresh procedure, parsing the response. Our
  plugin not as universal, but it is very convenient for Google APIs.
