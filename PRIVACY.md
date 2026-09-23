# Política de privacidad de ImageSorter

_Última actualización: 23 de septiembre de 2026_

ImageSorter es una app para ordenar las fotos y videos del teléfono: el usuario decide cuáles conservar, cuáles
mandar a la papelera y cuáles mover a Favoritos o Liked.

## Datos que recopila

**Ninguno.** ImageSorter no tiene permiso de acceso a Internet: no envía fotos, videos, datos de uso, identificadores
ni ninguna otra información fuera del teléfono. No incluye publicidad, analíticas ni servicios de terceros.

## Datos que guarda en el teléfono

- **Decisiones de revisión**: qué fotos ya revisaste y qué decidiste con cada una, para no volver a mostrártelas y
  para ejecutar los cambios solo cuando confirmas.
- **Carpetas archivadas** y **ajustes** (por ejemplo, la distancia para deslizar).

Estos datos se guardan solo dentro de la app, no se incluyen en copias de seguridad y se borran al desinstalarla.

## Permisos que usa y para qué

| Permiso | Para qué |
|---|---|
| Fotos y videos (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE` en Android 10–12) | Mostrar las fotos y videos para que decidas qué hacer con ellos. Es la función principal de la app. |
| Ubicación de las fotos (`ACCESS_MEDIA_LOCATION`) | Conservar los datos de ubicación de la foto al moverla a otra carpeta. La app no lee ni muestra la ubicación. |
| Almacenamiento (`WRITE_EXTERNAL_STORAGE`, solo Android 10) | Borrar y mover las fotos que confirmes. Android 10 no tiene otra forma de hacerlo. |
| Gestión de multimedia (`MANAGE_MEDIA`, opcional) | Mover fotos a la papelera o a otra carpeta sin que el sistema pida confirmación en cada lote. Se concede en Ajustes y se puede quitar cuando quieras. |

## Borrado de fotos

La app nunca borra una foto directamente: la manda a la papelera del sistema, donde Android la conserva unos 30 días
y se puede restaurar. Solo se borra definitivamente si lo pides desde la pantalla de papelera y lo confirmas dos veces.

**Android 10 no tiene papelera:** ahí lo que confirmes para borrar se elimina para siempre. La app lo avisa en la
pantalla principal y en la doble confirmación.

## Niños

La app no está dirigida a niños y no recopila datos de nadie.

## Cambios y contacto

Si esta política cambia, se actualizará en esta página. Para dudas, abre un _issue_ en
https://github.com/Nispeter/ImageSorter/issues.
