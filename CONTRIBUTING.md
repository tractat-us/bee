# Contributing to bee

bee is licensed under the [AGPL-3.0](LICENSE).

**Every contribution requires a signed contributor agreement that assigns all rights in the
contribution to the project owner, Iain Keddie.** No pull request from an outside contributor is
merged without one. This keeps the project free to relicense, for example to move a piece into the
Apache-licensed [kuilt](https://github.com/tractat-us/kuilt) library.

The agreement text and the signing process aren't published yet. Until they are, outside
contributions can't be accepted. Open an issue instead.

## Checks

Every pull request runs [`ci.yml`](.github/workflows/ci.yml). Its `ci-required` job is the one
check a merge needs. A docs change runs a check of the Markdown's relative links; the Gradle build
runs once the repository has one. Draft pull requests are skipped and show `ci-required` as
failed; mark the PR ready for review to run the checks.
