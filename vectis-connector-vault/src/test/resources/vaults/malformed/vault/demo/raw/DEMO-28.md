---
kind: story
id: DEMO-28
title: Alias expansion bomb
status: TO DO
a: &a ["x", "x", "x", "x", "x", "x", "x", "x", "x"]
b: &b [*a, *a, *a, *a, *a, *a, *a, *a, *a]
c: &c [*b, *b, *b, *b, *b, *b, *b, *b, *b]
---
