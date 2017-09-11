# :link: Ligoj Confluence plugin [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.ligoj.plugin/plugin-vm-aws/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.ligoj.plugin/plugin-vm-aws)

[![Build Status](https://travis-ci.org/ligoj/plugin-vm-aws.svg?branch=master)](https://travis-ci.org/ligoj/plugin-vm-aws)
[![Build Status](https://circleci.com/gh/ligoj/plugin-vm-aws.svg?style=svg)](https://circleci.com/gh/ligoj/plugin-vm-aws)
[![Build Status](https://codeship.com/projects/68badea0-0034-0135-2257-76737245ebb2/status?branch=master)](https://codeship.com/projects/212508)
[![Build Status](https://semaphoreci.com/api/v1/ligoj/plugin-vm-aws/branches/master/shields_badge.svg)](https://semaphoreci.com/ligoj/plugin-vm-aws)
[![Build Status](https://ci.appveyor.com/api/projects/status/ivche15v2p1962xe/branch/master?svg=true)](https://ci.appveyor.com/project/ligoj/plugin-vm-aws/branch/master)
[![Coverage Status](https://coveralls.io/repos/github/ligoj/plugin-vm-aws/badge.svg?branch=master)](https://coveralls.io/github/ligoj/plugin-vm-aws?branch=master)
[![Dependency Status](https://www.versioneye.com/user/projects/58caeda8dcaf9e0041b5b978/badge.svg?style=flat)](https://www.versioneye.com/user/projects/58caeda8dcaf9e0041b5b978)
[![Quality Gate](https://sonarqube.com/api/badges/gate?key=org.ligoj.plugin:plugin-vm-aws)](https://sonarqube.com/dashboard/index/org.ligoj.plugin:plugin-vm-aws)
[![Sourcegraph Badge](https://sourcegraph.com/github.com/ligoj/plugin-vm-aws/-/badge.svg)](https://sourcegraph.com/github.com/ligoj/plugin-vm-aws?badge)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/7972cb9a10d54d119b8c434fef8d4013)](https://www.codacy.com/app/ligoj/plugin-vm-aws?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=ligoj/plugin-vm-aws&amp;utm_campaign=Badge_Grade)
[![CodeFactor](https://www.codefactor.io/repository/github/ligoj/plugin-vm-aws/badge)](https://www.codefactor.io/repository/github/ligoj/plugin-vm-aws)
[![License](http://img.shields.io/:license-mit-blue.svg)](http://gus.mit-license.org/)

[Ligoj](https://github.com/ligoj/ligoj) EC2 AWS plugin, and extending [VM plugin](https://github.com/ligoj/plugin-vm)
Provides the following features :
- Supported operations from the [VM plugin](https://github.com/ligoj/plugin-vm) : ON, OFF, REBOOT, RESTART. No suspend or resume.
- Use AWS secret and access key with AWS API 4.0

Dashboard features :
- Status of the VM, including the intermediate busy mode
