import { keycloak } from '@/lib/keycloak'
import { useOnline } from '@/lib/offline/use-online'
import { MENU, type MenuItem } from '@/menu/registry'

export function useMenu(): { items: MenuItem[]; online: boolean } {
  const online = useOnline()
  const roles: string[] = keycloak.tokenParsed?.realm_access?.roles ?? []
  const items = MENU.filter((m) => m.roles.some((r) => roles.includes(r)))
  return { items, online }
}
