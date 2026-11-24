// build 后把 dist 拷进 Spring Boot 静态目录（classpath:/static/admin/）
import { cpSync, rmSync, mkdirSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
const dest = resolve(here, '../../src/main/resources/static/admin')

rmSync(dest, { recursive: true, force: true })
mkdirSync(dest, { recursive: true })
cpSync(dist, dest, { recursive: true })
console.log('deployed dist ->', dest)
