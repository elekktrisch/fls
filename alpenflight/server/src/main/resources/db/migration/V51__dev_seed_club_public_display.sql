
UPDATE t_club
   SET city = 'Zürich',
       logo_url = 'data:image/svg+xml,'
                  || '%3Csvg%20xmlns%3D%22http%3A//www.w3.org/2000/svg%22%20'
                  || 'viewBox%3D%220%200%2048%2048%22%3E%3Crect%20width%3D%2248%22%20'
                  || 'height%3D%2248%22%20fill%3D%22%230ea5e9%22/%3E%3Ctext%20x%3D%2224%22%20'
                  || 'y%3D%2231%22%20font-family%3D%22sans-serif%22%20font-size%3D%2220%22%20'
                  || 'fill%3D%22white%22%20text-anchor%3D%22middle%22%3ESC%3C/text%3E%3C/svg%3E'
 WHERE id = '019e30c3-2c00-7001-8000-000000000001'
   AND (city IS NULL OR logo_url IS NULL);
