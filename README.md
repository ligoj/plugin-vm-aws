# :link: Ligoj AWS EC2 plugin [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.ligoj.plugin/plugin-vm-aws/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.ligoj.plugin/plugin-vm-aws) [![Download](https://api.bintray.com/packages/ligoj/maven-repo/plugin-vm-aws/images/download.svg) ](https://bintray.com/ligoj/maven-repo/plugin-vm-aws/_latestVersion)

[![Build Status](https://travis-ci.com/ligoj/plugin-vm-aws.svg?branch=master)](https://travis-ci.com/ligoj/plugin-vm-aws)
[![Build Status](https://circleci.com/gh/ligoj/plugin-vm-aws.svg?style=svg)](https://circleci.com/gh/ligoj/plugin-vm-aws)
[![Build Status](https://ci.appveyor.com/api/projects/status/ivche15v2p1962xe/branch/master?svg=true)](https://ci.appveyor.com/project/ligoj/plugin-vm-aws/branch/master)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=org.ligoj.plugin%3Aplugin-vm-aws&metric=coverage)](https://sonarcloud.io/dashboard?id=org.ligoj.plugin%3Aplugin-vm-aws)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?metric=alert_status&project=org.ligoj.plugin:plugin-vm-aws)](https://sonarcloud.io/dashboard/index/org.ligoj.plugin:plugin-vm-aws)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/36ca446c091540289d23fe47f5027c0d)](https://www.codacy.com/app/ligoj/plugin-vm-aws?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=ligoj/plugin-vm-aws&amp;utm_campaign=Badge_Grade)
[![CodeFactor](https://www.codefactor.io/repository/github/ligoj/plugin-vm-aws/badge)](https://www.codefactor.io/repository/github/ligoj/plugin-vm-aws)
[![License](http://img.shields.io/:license-mit-blue.svg)](http://fabdouglas.mit-license.org/)

[Ligoj](https://github.com/ligoj/ligoj) EC2 AWS plugin, and extending [VM plugin](https://github.com/ligoj/plugin-vm)
Provides the following features :
- Supported operations from the [VM plugin](https://github.com/ligoj/plugin-vm) : ON, OFF, REBOOT, RESTART. No suspend or resume.
- Use AWS secret and access key with AWS API 4.0

Dashboard features :
- Status of the VM, including the intermediate busy mode

Note [Sheduled Lambda](http://docs.aws.amazon.com/lambda/latest/dg/with-scheduled-events.html) could be used instead on REST calls :
- There are limits : 100/500
- Changes management are more complex : update CRON, delete VM, add operation
