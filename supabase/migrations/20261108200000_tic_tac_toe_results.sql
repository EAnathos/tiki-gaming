-- Résultats des vérifications serveur des parties de Tic-Tac-Toe (POST /api/games/tic-tac-toe/verify) :
-- écrits uniquement par le backend Java (service_role), jamais lisibles ni modifiables par les joueurs.
-- Une partie vérifiée est forcément terminée : le statut IN_PROGRESS est refusé par la contrainte.
create table public.tic_tac_toe_results (
  id         uuid primary key default gen_random_uuid(),
  status     text not null check (status in ('X_WON', 'O_WON', 'DRAW')),
  moves      integer not null check (moves between 1 and 9),
  created_at timestamptz not null default now()
);

alter table public.tic_tac_toe_results enable row level security;
revoke all on public.tic_tac_toe_results from public, anon, authenticated, service_role;
grant select, insert on public.tic_tac_toe_results to service_role;
