/** 全局领域类型：与后端 JSON 结构一一对应 */

export interface CurrentUser {
  user: string
  displayName: string
  groups: string[]
  tenant: string
  admin: boolean
  anonymous: boolean
}

export interface MenuItem {
  code?: string
  name: string
  href: string
  permissions: string[]
  allowed: boolean
  icon?: string
  group?: string
}

export interface NavGroup {
  code: string
  name: string
  order: number
}

export interface RoleItem {
  role: string
  name: string
  permissions: string[]
  builtin: boolean
}

export interface PermissionNode {
  code: string
  name: string
  nodeType: 'directory' | 'menu' | 'btn'
  module: string
  description: string
  builtin: boolean
  parentCode: string
  routePath: string
  orderNum: number
  icon?: string
  retired?: boolean
}

export interface PermissionsResponse {
  user: string
  displayName?: string
  groups: string[]
  tenant: string
  permissions: string[]
  allPermissions: string[]
  roleMatrix: RoleItem[]
  menus: MenuItem[]
  navGroups: NavGroup[]
  admin: boolean
  anonymous: boolean
}

export interface LoginResult {
  accessToken: string
  refreshToken: string
  user: string
  displayName: string
  groups: string[]
  tenant: string
}

/** 统一 API 错误：携带用户可读文案，不暴露 HTTP 状态码 */
export class AppError extends Error {
  constructor(message: string, public readonly status?: number) {
    super(message)
    this.name = 'AppError'
  }
}

export interface PageState<T> {
  page: number
  size: number
  total: number
  rows: T[]
  loading: boolean
  error: string
}
