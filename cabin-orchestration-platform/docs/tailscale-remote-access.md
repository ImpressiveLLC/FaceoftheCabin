# Tailscale Remote Access — Setup Runbook

## Install on Lenovo host (Ubuntu 24.04)

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up \
  --advertise-routes=192.168.1.0/24 \
  --accept-routes \
  --hostname=cabin-hub \
  --ssh
```

Login with smrekarfamilia@gmail.com Google SSO.

## Enable MagicDNS and subnet routing

In Tailscale admin console (login.tailscale.com):
- Devices → cabin-hub → Enable subnet routes
- DNS → Enable MagicDNS

## Access URLs from anywhere (phone, laptop)

| Service            | URL |
|--------------------|-----|
| Cabin Hub UI       | http://cabin-hub:5173 |
| Spring Boot API    | http://cabin-hub:8080 |
| Grafana            | http://cabin-hub:3000 |
| Node-RED           | http://cabin-hub:1880 |
| Home Assistant     | http://cabin-hub:8123 |
| Frigate NVR        | http://cabin-hub:5000 |

## ACL policy (tailscale admin → ACLs)

```json
{
  "acls": [
    {
      "action": "accept",
      "src": ["autogroup:owner"],
      "dst": ["cabin-hub:*"]
    }
  ]
}
```

## Google service access

Tailscale tunnel provides access to the HA Google integrations and the
Familia Hub Google OAuth without any additional port-forwarding.
The existing smrekarfamilia@gmail.com OAuth app credentials (client_id/secret)
already issued to the Familia Hub can be reused here — add
http://cabin-hub:8080/oauth2/callback as an authorized redirect URI in the
Google Cloud Console for that project.
